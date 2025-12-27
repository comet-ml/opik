"""Isolated subprocess executor with environment variable scoping."""
import json
import logging
import os
import resource
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, Callable, List
from threading import Lock

from opik_backend.subprocess_log_config import SubprocessLogConfig

logger = logging.getLogger(__name__)

# Metrics setup
from opentelemetry import metrics

meter = metrics.get_meter("isolated_executor")
isolated_creation_histogram = meter.create_histogram(
    name="isolated_subprocess_creation_latency",
    description="Latency of isolated subprocess creation in milliseconds",
    unit="ms",
)
isolated_execution_histogram = meter.create_histogram(
    name="isolated_subprocess_execution_latency",
    description="Latency of isolated code execution in milliseconds",
    unit="ms",
)

active_process_counter = meter.create_up_down_counter(
    name="isolated_subprocess_active_count",
    description="Current number of active isolated subprocesses",
    unit="1",
)

# Memory limit for subprocesses in bytes (20MB)
SUBPROCESS_MEMORY_LIMIT_BYTES = 20 * 1024 * 1024  # 20MB

def _calculate_latency_ms(start_time):
    """Calculate elapsed time in milliseconds."""
    return (time.time() - start_time) * 1000

def _set_memory_limit():
    """Set memory limit for subprocess to 20MB.
    
    Uses RLIMIT_STACK to limit only stack size.
    This prevents deeply nested calls and excessive local variables
    while allowing the Python interpreter and runtime heap to function normally.
    """
    try:
        # RLIMIT_STACK limits stack size only (local variables, call stack depth)
        # Prevents stack overflow from deeply nested recursion
        # Does NOT limit heap or runtime data structures
        resource.setrlimit(resource.RLIMIT_STACK, (SUBPROCESS_MEMORY_LIMIT_BYTES, SUBPROCESS_MEMORY_LIMIT_BYTES))
    except Exception as e:
        logger.warning(f"Failed to set stack memory limit: {e}")

class IsolatedSubprocessExecutor:
    """
    Executes Python code in isolated subprocesses with environment variable scoping.

    Each execution creates a fresh subprocess, ensuring that:
    - Environment variables are scoped to each execution (no leakage between concurrent runs)
    - Subprocesses are completely independent
    - No shared state exists between executions
    - Resources are properly cleaned up after each execution

    This differs from ProcessExecutor which maintains a pool of reusable workers.
    Use this when you need true isolation with custom environment variables per execution.
    """

    def __init__(self, timeout_secs: int = 30):
        """
        Initialize the isolated subprocess executor.

        Args:
            timeout_secs: Timeout for each execution in seconds (default: 30)
        """
        self.timeout_secs = timeout_secs
        self.logger = logging.getLogger(__name__)
        self._active_processes: List[subprocess.Popen] = []  # Track active processes for cleanup
        self._process_lock = Lock()
        self._teardown_callbacks: List[Callable[[], None]] = []  # Callbacks to run on teardown
        self._log_collectors = {}  # Map process PID to log collector instance

    def execute(
        self,
        file_path: str,
        data: dict = {},
        env_vars: Optional[dict] = None,
        timeout_secs: Optional[int] = None,
        payload_type: Optional[str] = None,
        optimization_id: Optional[str] = None,
        job_id: Optional[str] = None,
    ) -> dict:
        """
        Execute Python file in an isolated subprocess with scoped environment variables.

        Each call creates a fresh subprocess with its own isolated environment.
        Environment variables passed in env_vars are scoped to the subprocess
        and don't affect other concurrent executions.

        Args:
            file_path: Path to Python file to execute (e.g., '/path/to/metric.py')
            data: Data dictionary to pass to the file via stdin
            env_vars: Environment variables to scope to this subprocess (optional).
                     These override/augment the parent environment for this execution only.
            timeout_secs: Execution timeout in seconds (uses default if not provided)
            payload_type: Type of payload being executed (e.g., 'trace_thread')
            optimization_id: Optimization identifier for log correlation
            job_id: Job identifier for log correlation

        Returns:
            dict: Result dictionary with format:
                - {"scores": [...]} on success
                - {"code": error_code, "error": message} on failure
        """
        timeout_secs = timeout_secs or self.timeout_secs
        if data is None:
            data = {}
        creation_start = time.time()
        process = None  # Initialize to None for exception handling
        result = None

        try:
            if env_vars is None:
                env_vars = {}
            # Prepare environment for subprocess
            subprocess_env = self._prepare_environment(env_vars)

            # Create subprocess with python to execute the file directly
            process = subprocess.Popen(
                [sys.executable, "-u", file_path],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=subprocess_env,
                text=True,
                bufsize=1,
                preexec_fn=_set_memory_limit,  # Apply memory limit to subprocess
            )

            # Track active process
            self._active_processes.append(process)

            creation_latency = _calculate_latency_ms(creation_start)
            isolated_creation_histogram.record(creation_latency)
            self.logger.debug(
                f"Created isolated subprocess. pid={process.pid}, creation_latency_ms={creation_latency:.3f}"
            )

            # Execute code in subprocess
            result = self._execute_in_subprocess(
                process,
                data,
                payload_type,
                timeout_secs,
                optimization_id=optimization_id,
                job_id=job_id,
                env_vars=env_vars or {},
            )

            execution_latency = _calculate_latency_ms(creation_start)
            isolated_execution_histogram.record(execution_latency)
            self.logger.debug(
                f"Isolated subprocess execution completed. total_latency_ms={execution_latency:.3f}"
            )

            return result

        except subprocess.TimeoutExpired:
            self.logger.error(
                f"Subprocess execution timed out. timeout_secs={timeout_secs}"
            )
            return {
                "code": 500,
                "error": f"Execution timed out after {timeout_secs} seconds",
            }
        except Exception as e:
            self.logger.error(
                f"Error during subprocess execution. error={str(e)}", exc_info=True
            )
            return {"code": 500, "error": f"Failed to execute file: {str(e)}"}
        finally:
            # Always remove process from active list and measure total latency
            self._remove_active_process(process)
            total_latency = _calculate_latency_ms(creation_start)
            self.logger.debug(
                f"Subprocess execution finished. total_latency_ms={total_latency:.3f}"
            )

    def _remove_active_process(self, process: Optional[subprocess.Popen]) -> None:
        """Remove process from active processes list if it exists."""
        if process in self._active_processes:
            self._active_processes.remove(process)

    def _prepare_environment(self, env_vars: Optional[dict] = None) -> dict:
        """
        Prepare environment variables for the subprocess.

        Starts with a copy of the parent environment and applies overrides.
        This ensures the subprocess has all necessary environment variables
        while allowing specific variables to be scoped to this execution.

        Args:
            env_vars: Environment variables to override/add

        Returns:
            dict: Complete environment dictionary for the subprocess
        """
        env = os.environ.copy()
        if env_vars:
            # Filter out None values and log them - subprocess.Popen requires all values to be strings
            for key, value in env_vars.items():
                if value is None:
                    self.logger.warning(f"Skipping environment variable '{key}' with None value")
                else:
                    env[key] = value

        env["PYTHONUNBUFFERED"] = '1'
        env["LOG_FORMAT"] = 'json'
        return env

    def _execute_in_subprocess(
        self,
        process: subprocess.Popen,
        data: dict,
        payload_type: Optional[str],
        timeout_secs: int,
        optimization_id: str = None,
        job_id: str = None,
        env_vars: dict = {},
    ) -> dict:
        """
        Execute code in the subprocess and collect result.

        Uses python -c to execute code inline with stdin for data passing.
        Streams stderr to the logging backend in real-time if configured.

        Args:
            process: Subprocess Popen instance
            data: Data dictionary
            payload_type: Type of payload
            timeout_secs: Execution timeout
            optimization_id: Optional ID for log correlation
            job_id: Optional ID for log correlation

        Returns:
            dict: Execution result
        """
        # Prepare input as JSON to pass via stdin
        input_json = json.dumps(
            {
                "data": data,
                "payload_type": payload_type,
            }
        )

        try:
            # Initialize logger BEFORE process starts
            # Check if a log collector was pre-registered (e.g., for optimization jobs)
            pre_registered_collector = self._log_collectors.pop(0, None)  # 0 is placeholder key
            
            if pre_registered_collector is not None:
                # Use pre-registered collector (e.g., RedisBatchLogCollector for optimizations)
                self._log_collectors[process.pid] = pre_registered_collector
            elif SubprocessLogConfig.is_fully_configured():
                # Fallback to HTTP-based logging if configured
                try:
                    from opik_backend.subprocess_logger import BatchLogCollector
                    self._log_collectors[process.pid] = BatchLogCollector(
                        backend_url=SubprocessLogConfig.get_backend_url(),
                        optimization_id=optimization_id or "",
                        job_id=job_id or "",
                        api_key=env_vars.get("OPIK_API_KEY", ""),
                        workspace=env_vars.get("OPIK_WORKSPACE", ""),
                    )
                except ImportError as e:
                    self.logger.error(f"Subprocess logging is configured but BatchLogCollector import failed: {e}")
                    raise
                except Exception as e:
                    self.logger.error(f"Failed to initialize subprocess logging: {e}")
            
            # Decide execution strategy based on logging configuration
            if self._log_collectors.get(process.pid):
                # Real-time streaming: start log collector threads, then wait for process
                self._log_collectors[process.pid].start_stream_from_process(process)
                
                # Send input to the process
                process.stdin.write(input_json)
                process.stdin.close()  # Signal EOF so process knows input is done
                
                # Wait for process to complete
                process.wait(timeout=timeout_secs)
                
                # Wait for reader threads to finish reading all output
                self._log_collectors[process.pid].wait_for_reader_threads(timeout=SubprocessLogConfig.get_log_reader_timeout_secs())
                
                # Get stdout/stderr from last lines (no memory accumulation)
                # Safe to do now that threads have finished
                last_lines = self._log_collectors[process.pid].get_last_lines()
                stdout = last_lines.get('stdout', '')
                stderr = last_lines.get('stderr', '')
            else:
                # Simple mode: use communicate() without logging overhead
                stdout, stderr = process.communicate(input=input_json, timeout=timeout_secs)

            # Parse result from stdout
            if process.returncode == 0:
                try:
                    # Extract last non-empty line from stdout as the result JSON
                    lines = [line for line in stdout.split('\n') if line.strip()]
                    if not lines:
                        raise ValueError("No output produced by subprocess")
                    result = json.loads(lines[-1])
                    return result
                except (json.JSONDecodeError, ValueError) as e:
                    self.logger.error(f"Failed to parse subprocess output: {stdout}")
                    return {
                        "code": 500,
                        "error": f"Invalid JSON response from subprocess: {str(e)}",
                    }
            else:
                self.logger.error(
                    f"Subprocess exited with code {process.returncode}. stderr: {stderr}"
                )
                return {
                    "code": 500,
                    "error": f"Subprocess execution failed: {stderr}",
                }
        
        except subprocess.TimeoutExpired:
            process.kill()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
            raise
        finally:
            # Close log collector immediately after execution completes to flush all logs
            # This ensures cleanup happens even if teardown() is not called
            self._close_log_collector(process.pid)

    def _close_log_collector(self, pid: int):
        """Close the log collector for a specific process if it exists.
        
        This properly signals shutdown, waits for any pending flushes, 
        and cleans up all threads.
        """
        try:
            with self._process_lock:
                if pid in self._log_collectors:
                    # close() handles: signal stop -> shutdown executor -> final flush -> cleanup threads
                    self._log_collectors[pid].close()
                    del self._log_collectors[pid]
        except Exception as e:
            self.logger.warning(f"Error closing log collector for PID {pid}: {e}")

    def register_teardown_callback(self, callback):
        """
        Register a callback to run during teardown.

        Args:
            callback: A callable that takes no arguments and runs cleanup logic
        """
        self._teardown_callbacks.append(callback)

    def kill_process(self, pid: int, timeout: int = 2):
        """
        Terminate a specific process by PID.

        Args:
            pid: Process ID to terminate
            timeout: Seconds to wait before force killing

        Returns:
            bool: True if process was terminated, False if not found
        """
        try:
            # Atomically find and remove process from active list
            with self._process_lock:
                process = next((p for p in self._active_processes if p.pid == pid), None)
                if process is None:
                    self.logger.warning(f"Process with PID {pid} not found in active processes")
                    return False
                self._active_processes.remove(process)
            
            # Now kill the process (outside lock to avoid long hold times)
            process.terminate()
            try:
                process.wait(timeout=timeout)
                self.logger.info(f"Process {pid} terminated gracefully")
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                self.logger.warning(f"Process {pid} force killed after timeout")
            
            # Close log collector after process is terminated to capture any final logs
            self._close_log_collector(pid)
            
            return True
        except Exception as e:
            self.logger.error(f"Error killing process {pid}: {e}")
            return False

    def kill_all_processes(self, timeout: int = 2):
        """
        Terminate all active processes in parallel.

        Args:
            timeout: Total seconds to wait for all processes (distributed across them)
        """
        # Collect PIDs first to avoid modification during iteration
        with self._process_lock:
            pids = [p.pid for p in list(self._active_processes)]
        
        if not pids:
            self.logger.info("No active processes to terminate")
            return
        
        # Kill processes in parallel using ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=len(pids)) as executor:
            futures = [
                executor.submit(self.kill_process, pid, timeout)
                for pid in pids
            ]
            # Wait for all to complete (with timeout for safety)
            for future in futures:
                try:
                    future.result(timeout=timeout)
                except Exception as e:
                    self.logger.warning(f"Error waiting for kill_process result: {e}")
        
        self.logger.info("All active processes terminated")

    def teardown(self):
        """
        Run cleanup logic including killing all processes and executing teardown callbacks.
        """
        self.logger.info("Running teardown...")
        
        # Kill all active processes (which will also close their log collectors)
        self.kill_all_processes()
        
        # Close any remaining log collectors that weren't cleaned up
        for pid in list(self._log_collectors.keys()):
            try:
                self._close_log_collector(pid)
            except Exception as e:
                self.logger.error(f"Error closing remaining log collector for PID {pid}: {e}")
        
        # Execute all teardown callbacks
        for callback in self._teardown_callbacks:
            try:
                callback()
            except Exception as e:
                self.logger.error(f"Error in teardown callback: {e}")
        
        self.logger.info("Teardown complete")

    def __enter__(self):
        """Context manager entry."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit - runs teardown."""
        self.teardown()
        return False
