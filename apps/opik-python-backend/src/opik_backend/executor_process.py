import atexit
import concurrent.futures
import json
import logging
import os
import subprocess
import time
import uuid
from queue import Queue
from threading import Lock, Event
from uuid6 import uuid7

import schedule
from opentelemetry import metrics

from opik_backend.executor import CodeExecutorBase

logger = logging.getLogger(__name__)

# Create a meter for Process executor metrics
meter = metrics.get_meter("process_executor")

# Create histogram metrics for process operations
process_creation_histogram = meter.create_histogram(
    name="process_creation_latency",
    description="Latency of process creation operations in milliseconds",
    unit="ms",
)

process_execution_histogram = meter.create_histogram(
    name="process_execution_latency",
    description="Latency of code execution in process in milliseconds",
    unit="ms",
)

# Create a gauge metric to track the number of available processes in the pool
process_pool_size_gauge = meter.create_gauge(
    name="process_pool_size",
    description="Number of available processes in the pool queue",
)


class ProcessExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        self.instance_id = str(uuid7())
        self.process_pool = Queue()
        self.pool_lock = Lock()
        self.releaser_executor = concurrent.futures.ThreadPoolExecutor(max_workers=4)
        self.stop_event = Event()
        self.pool_check_interval = int(os.getenv("PYTHON_CODE_EXECUTOR_POOL_CHECK_INTERVAL_IN_SECONDS", "3"))
        
        # Pre-warm the process pool
        self._pre_warm_process_pool()

        # Start the pool monitor
        self._start_pool_monitor()

        atexit.register(self.cleanup)

    def _start_pool_monitor(self):
        """Start a background thread that periodically checks and fills the process pool."""
        logger.info(f"Starting process pool monitor with {self.pool_check_interval} second interval")

        # Schedule the pool check to run at the configured interval
        schedule.every(self.pool_check_interval).seconds.do(self._check_pool)

        # Start a background thread to run the scheduler
        self.releaser_executor.submit(self._run_scheduler)

    def _check_pool(self):
        """Check and fill the process pool if needed."""
        if self.stop_event.is_set():
            logger.info("Process pool monitor stopped")
            return schedule.CancelJob  # Cancel this job

        try:
            # Update the process pool size metric
            self._update_process_pool_size_metric()

            self.ensure_pool_filled()
            return None  # Continue the job
        except Exception as e:
            logger.error(f"Error in pool monitor: {e}")
            return None  # Continue the job despite the error

    def _run_scheduler(self):
        """Run the scheduler in a background thread."""
        logger.info("Starting scheduler for process pool monitoring")
        while not self.stop_event.is_set():
            schedule.run_pending()
            time.sleep(1)  # Sleep to avoid busy-waiting

        logger.info("Scheduler finished running")

    def _pre_warm_process_pool(self):
        """
        Pre-warm the process pool by creating all processes in parallel.
        This ensures processes are ready when the service starts.
        """
        logger.info(f"Pre-warming process pool with {self.max_parallel} processes")
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel) as pool_init:
            # Submit process creation tasks in parallel
            futures = [pool_init.submit(self.create_worker_process) for _ in range(self.max_parallel)]
            # Wait for all processes to be created
            concurrent.futures.wait(futures)

    def ensure_pool_filled(self):
        """Ensure the process pool has enough processes."""
        if self.stop_event.is_set():
            logger.warning("Executor is shutting down, skipping process creation")
            return

        with self.pool_lock:
            current_pool_size = self.process_pool.qsize()
            to_create = self.max_parallel - current_pool_size
            if to_create > 0:
                logger.info(f"Not enough python runner processes; creating {to_create} more...")
                for _ in range(to_create):
                    self.create_worker_process()

    def _update_process_pool_size_metric(self):
        """Update the process pool size metric with the current number of processes in the pool."""
        pool_size = self.process_pool.qsize()
        process_pool_size_gauge.set(pool_size)
        logger.debug(f"Current process pool size: {pool_size}")
        return pool_size

    def _calculate_latency_ms(self, start_time):
        """Calculate elapsed time in milliseconds."""
        return (time.time() - start_time) * 1000  # Convert to milliseconds

    def create_worker_process(self):
        """Create a new worker process that can be used for execution."""
        # Record the start time for detailed process creation metrics
        start_time = time.time()

        try:
            # Create a unique ID for this worker
            worker_id = str(uuid.uuid4())[:8]

            # Path to the worker script (assuming it's in the same directory as this file)
            import os.path
            worker_script_path = os.path.join(os.path.dirname(__file__), "process_worker.py")

            # Start the worker process using the dedicated worker script file
            process = subprocess.Popen(
                ["python", "-u", worker_script_path],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=1,  # Line-buffered
                universal_newlines=True  # Text mode for easy line reading
            )

            # Wait for the "READY" signal from the worker with timeout
            try:
                ready_line = ""
                # Set up a thread to read stderr in background to catch initialization errors
                stderr_lines = []
                def read_stderr():
                    while True:
                        line = process.stderr.readline()
                        if not line:
                            break
                        stderr_lines.append(line.strip())
                
                stderr_thread = concurrent.futures.ThreadPoolExecutor(max_workers=1)
                stderr_future = stderr_thread.submit(read_stderr)
                
                # Wait for READY with timeout
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as ready_pool:
                    ready_future = ready_pool.submit(process.stdout.readline)
                    try:
                        ready_line = ready_future.result(timeout=5)  # 5 second timeout for startup
                        ready_line = ready_line.strip()
                    except concurrent.futures.TimeoutError:
                        # If we time out waiting for READY, check if the process is still alive
                        if process.poll() is not None:
                            # Process has exited
                            exit_code = process.poll()
                            logger.error(f"Worker {worker_id} exited with code {exit_code} during initialization")
                            # Get any stderr output
                            if stderr_lines:
                                logger.error(f"Worker {worker_id} stderr output: {stderr_lines}")
                            raise Exception(f"Worker process exited with code {exit_code} during startup")
                        else:
                            logger.error(f"Worker {worker_id} timed out waiting for READY signal")
                            process.kill()
                            raise Exception("Worker process timed out during startup")
                
                # Check if we got the expected READY signal
                if ready_line != "READY":
                    # Process is alive but didn't respond with READY
                    logger.warning(f"Worker {worker_id} sent unexpected ready signal: '{ready_line}'")
                    if stderr_lines:
                        logger.error(f"Worker {worker_id} stderr output: {stderr_lines}")
                    # Still try to use the process, but log the warning
            
            except Exception as e:
                # Clean up the process if anything goes wrong
                if process.poll() is None:
                    process.kill()
                logger.error(f"Failed to initialize worker {worker_id}: {e}")
                if stderr_lines:
                    logger.error(f"Worker {worker_id} stderr output: {stderr_lines}")
                raise

            # Store the worker process info
            worker = {
                'id': worker_id,
                'process': process,
                'creation_time': time.time()
            }

            # Add the worker to the pool
            self.process_pool.put(worker)

            # Calculate and record the latency
            latency = self._calculate_latency_ms(start_time)
            process_creation_histogram.record(latency, attributes={"method": "create_process"})

            logger.info(f"Created worker process {worker_id}, pid {process.pid} in {latency:.3f} milliseconds")
            return worker
        except Exception as e:
            logger.error(f"Failed to create worker process: {e}")
            return None

    def terminate_worker(self, worker):
        """Terminate a worker process."""
        try:
            process = worker.get('process')
            if not process:
                return

            worker_id = worker.get('id', 'unknown')

            # Try to gracefully exit first
            try:
                if process.poll() is None:  # If process is still running
                    process.stdin.write("EXIT\n")
                    process.stdin.flush()
                    process.wait(timeout=1)
            except Exception:
                pass

            # Force kill if still running
            if process.poll() is None:
                try:
                    process.terminate()
                    process.wait(timeout=1)
                except Exception:
                    pass

            # Last resort
            if process.poll() is None:
                try:
                    process.kill()
                except Exception:
                    pass

            logger.info(f"Terminated worker {worker_id}")

            # Create a replacement worker asynchronously
            self.releaser_executor.submit(self.create_worker_process)
        except Exception as e:
            logger.error(f"Error terminating worker: {e}")
            # Still try to create a replacement
            self.releaser_executor.submit(self.create_worker_process)

    def get_worker(self):
        """Get a worker from the pool with timeout handling."""
        if self.stop_event.is_set():
            raise RuntimeError("Executor is shutting down, no workers available")

        while not self.stop_event.is_set():
            try:
                worker = self.process_pool.get(timeout=self.exec_timeout)
                process = worker.get('process')

                # Verify the process is still running
                if process.poll() is not None:  # Process has exited
                    logger.warning(f"Worker {worker.get('id')} has exited unexpectedly, creating replacement")
                    self.terminate_worker(worker)  # Clean up and create replacement
                    continue  # Try another worker

                return worker
            except Exception as e:
                if self.stop_event.is_set():
                    raise RuntimeError("Executor is shutting down, no workers available")

                logger.warning(f"Couldn't get a worker after waiting for {self.exec_timeout}s. Will retry: {e}")

    def run_scoring(self, code: str, data: dict) -> dict:
        if self.stop_event.is_set():
            return {"code": 503, "error": "Service is shutting down"}

        worker = self.get_worker()
        start_time = time.time()

        try:
            process = worker.get('process')
            worker_id = worker.get('id', 'unknown')

            # Prepare the command data
            command_data = {
                'code': code,
                'data': data
            }

            # Send the command
            command_json = json.dumps(command_data) + "\n"
            process.stdin.write(command_json)
            process.stdin.flush()

            # Read the response with timeout using a thread
            with concurrent.futures.ThreadPoolExecutor() as exec_pool:
                future = exec_pool.submit(process.stdout.readline)
                try:
                    response_line = future.result(timeout=self.exec_timeout)
                    # If we got an empty response, the worker has died
                    if not response_line:
                        logger.error(f"Worker {worker_id} died during execution")
                        self.terminate_worker(worker)
                        return {"code": 500, "error": "Worker process died during execution"}

                    # Parse the response
                    try:
                        result = json.loads(response_line)

                        # Calculate and record latency
                        latency = self._calculate_latency_ms(start_time)
                        process_execution_histogram.record(latency, attributes={"method": "run_scoring"})
                        logger.debug(f"Worker {worker_id} executed code in {latency:.3f} milliseconds")

                        # Return the worker to the pool for reuse
                        self.process_pool.put(worker)

                        return result
                    except json.JSONDecodeError as e:
                        logger.error(f"Failed to decode response from worker {worker_id}: {e}")
                        logger.error(f"Response was: {response_line}")
                        self.terminate_worker(worker)
                        return {"code": 500, "error": "Failed to decode worker response"}
                except concurrent.futures.TimeoutError:
                    logger.error(f"Execution timed out in worker {worker_id}")
                    self.terminate_worker(worker)
                    return {"code": 504, "error": "Server processing exceeded timeout limit."}
        except Exception as e:
            logger.error(f"Error in run_scoring: {e}")
            if 'worker' in locals():
                self.terminate_worker(worker)
            return {"code": 500, "error": f"Failed to execute code: {str(e)}"}

    def cleanup(self):
        """Clean up resources used by this executor."""
        logger.info("Shutting down Process executor")
        self.stop_event.set()

        # Clear all scheduled jobs
        schedule.clear()

        # Clean up all worker processes
        while not self.process_pool.empty():
            try:
                worker = self.process_pool.get_nowait()
                self.terminate_worker(worker)
            except:
                pass

        # Shutdown the executor
        logger.info("Shutting down executor")
        self.releaser_executor.shutdown(wait=False, cancel_futures=True)
