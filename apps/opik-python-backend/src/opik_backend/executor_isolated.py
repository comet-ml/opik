"""Isolated subprocess executor with environment variable scoping."""
import json
import logging
import os
import resource
import subprocess
import sys
import time
from typing import Optional

from opik_backend.executor import CodeExecutorBase

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
            timeout_secs: Timeout for each execution in seconds
        """
        self.timeout_secs = timeout_secs
        self.logger = logging.getLogger(__name__)
        self._active_processes = []  # Track active processes for cleanup
        self._teardown_callbacks = []  # Callbacks to run on teardown

    def execute(
        self,
        code: str,
        data: dict,
        env_vars: Optional[dict] = None,
        timeout_secs: Optional[int] = None,
        payload_type: Optional[str] = None,
    ) -> dict:
        """
        Execute Python code in an isolated subprocess with scoped environment variables.

        Each call creates a fresh subprocess with its own isolated environment.
        Environment variables passed in env_vars are scoped to the subprocess
        and don't affect other concurrent executions.

        Args:
            code: Python code to execute or path to Python file
            data: Data dictionary to pass to the code
            env_vars: Environment variables to scope to this subprocess (optional).
                     These override/augment the parent environment for this execution only.
            timeout_secs: Execution timeout in seconds (uses default if not provided)
            payload_type: Type of payload being executed (e.g., 'trace_thread')

        Returns:
            dict: Result dictionary with format:
                - {"scores": [...]} on success
                - {"code": error_code, "error": message} on failure
        """
        timeout_secs = timeout_secs or self.timeout_secs
        creation_start = time.time()
        process = None  # Initialize before try block to ensure it's always defined

        try:
            # Prepare environment for subprocess
            subprocess_env = self._prepare_environment(env_vars)

            # Load code from file if needed
            loaded_code = self._load_code_from_file(code)

            # Create wrapper code that reads input from stdin and executes user code
            wrapper_code = self._create_wrapper_script(loaded_code)

            # Create subprocess with python -c to execute wrapper code
            process = subprocess.Popen(
                [sys.executable, "-u", "-c", wrapper_code],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=subprocess_env,
                text=True,
                preexec_fn=_set_memory_limit,  # Apply memory limit to subprocess
            )

            # Track active process
            self._active_processes.append(process)

            creation_latency = _calculate_latency_ms(creation_start)
            isolated_creation_histogram.record(creation_latency)
            self.logger.debug(
                f"Created isolated subprocess (PID: {process.pid}) in {creation_latency:.3f}ms"
            )

            # Execute code in subprocess
            execution_start = time.time()
            result = self._execute_in_subprocess(
                process, data, payload_type, timeout_secs
            )

            execution_latency = _calculate_latency_ms(execution_start)
            isolated_execution_histogram.record(execution_latency)
            self.logger.debug(
                f"Isolated subprocess execution completed in {execution_latency:.3f}ms"
            )

            # Remove from active processes
            if process in self._active_processes:
                self._active_processes.remove(process)

            return result

        except subprocess.TimeoutExpired as e:
            self.logger.error(
                f"Isolated subprocess execution timed out after {timeout_secs}s"
            )
            # Remove from active processes
            if process in self._active_processes:
                self._active_processes.remove(process)
            return {
                "code": 500,
                "error": f"Execution timed out after {timeout_secs} seconds",
            }
        except Exception as e:
            self.logger.error(
                f"Error during isolated subprocess execution: {e}", exc_info=True
            )
            return {"code": 500, "error": f"Failed to execute code: {str(e)}"}

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
            env.update(env_vars)
        return env

    def _load_code_from_file(self, code: str) -> str:
        """
        Load Python code from file path if code is a file path.

        Check if code is a valid file path before trying to open it.
        Only treats strings as file paths if:
        1. They end with .py
        2. They look like absolute or relative paths
        3. The file actually exists

        Args:
            code: Either a file path or Python code string

        Returns:
            str: Python code to execute
        """
        # First, check if this could be a file path
        if not code.endswith('.py'):
            # If it doesn't end with .py, it's probably code, not a path
            return code
        
        # If it ends with .py, try to load it as a file
        try:
            if os.path.isfile(code):
                with open(code, 'r') as f:
                    loaded_code = f.read()
                self.logger.debug(f"Loaded Python file from: {code}")
                return loaded_code
            else:
                # File doesn't exist, treat as code
                return code
        except (FileNotFoundError, IOError, OSError):
            # Error opening file, treat as code
            return code

    def _create_wrapper_script(self, code: str) -> str:
        """
        Create a wrapper script that reads input JSON from stdin and executes code.

        The wrapper:
        1. Reads JSON from stdin containing data and payload_type
        2. Makes data and payload_type available to user code
        3. Executes the user code
        4. Captures and outputs result as JSON to stdout

        Args:
            code: User's Python code to execute

        Returns:
            str: Python code that can be passed to python -c
        """
        # The wrapper reads input, makes variables available, and executes user code
        # User code should not read from stdin - it receives data as variables
        wrapper = f"""
import json
import sys
import traceback

try:
    # Read input from stdin (wrapper responsibility)
    input_data = json.loads(sys.stdin.read())
    data = input_data.get("data", {{}})
    payload_type = input_data.get("payload_type")

    # Execute user code in this namespace
    # User code has access to: data, payload_type, json, sys, traceback
{chr(10).join('    ' + line for line in code.split(chr(10)))}

    # Expected: User code should print JSON result to stdout
except Exception as e:
    error_result = {{"code": 500, "error": str(e), "traceback": traceback.format_exc()}}
    print(json.dumps(error_result))
    sys.exit(1)
"""
        return wrapper

    def _execute_in_subprocess(
        self,
        process: subprocess.Popen,
        data: dict,
        payload_type: Optional[str],
        timeout_secs: int,
    ) -> dict:
        """
        Execute code in the subprocess and collect result.

        Uses python -c to execute code inline with stdin for data passing.

        Args:
            process: Subprocess Popen instance
            data: Data dictionary
            payload_type: Type of payload
            timeout_secs: Execution timeout

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
            # Send data via stdin, collect stdout
            stdout, stderr = process.communicate(
                input=input_json,
                timeout=timeout_secs,
            )

            # Parse result from stdout
            if process.returncode == 0:
                try:
                    result = json.loads(stdout.strip())
                    return result
                except json.JSONDecodeError as e:
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
            process = next((p for p in self._active_processes if p.pid == pid), None)
            if process is None:
                self.logger.warning(f"Process with PID {pid} not found in active processes")
                return False
            
            process.terminate()
            try:
                process.wait(timeout=timeout)
                self.logger.info(f"Process {pid} terminated gracefully")
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                self.logger.warning(f"Process {pid} force killed after timeout")
            
            self._active_processes.remove(process)
            return True
        except Exception as e:
            self.logger.error(f"Error killing process {pid}: {e}")
            return False

    def kill_all_processes(self, timeout: int = 2):
        """
        Terminate all active processes.

        Args:
            timeout: Seconds to wait before force killing each process
        """
        for process in list(self._active_processes):
            try:
                process.terminate()
            except Exception as e:
                self.logger.error(f"Error terminating process {process.pid}: {e}")
        
        for process in list(self._active_processes):
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            except Exception as e:
                self.logger.error(f"Error waiting for process {process.pid}: {e}")
        
        self._active_processes.clear()
        self.logger.info("All active processes terminated")

    def teardown(self):
        """
        Run cleanup logic including killing all processes and executing teardown callbacks.
        """
        self.logger.info("Running teardown...")
        
        # Kill all active processes
        self.kill_all_processes()
        
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
