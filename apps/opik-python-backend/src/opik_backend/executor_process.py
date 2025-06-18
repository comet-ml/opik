import atexit
import concurrent.futures
import logging
import os
import signal
import time
import uuid
import sys
from multiprocessing import Process, Pipe
from queue import Queue, Empty
from threading import Lock, Event
from uuid6 import uuid7

import schedule
from opentelemetry import metrics

from opik_backend.executor import CodeExecutorBase
from opik_backend import process_worker

logger = logging.getLogger(__name__)

# OTel metrics setup (assuming this is standard and correct)
meter = metrics.get_meter("process_executor")
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
process_pool_size_gauge = meter.create_gauge(
    name="process_pool_size",
    description="Number of available processes in the pool queue",
)


def _calculate_latency_ms(start_time):
    return (time.time() - start_time) * 1000


class ProcessExecutor(CodeExecutorBase):
    def __init__(self, should_initialize: bool):
        super().__init__()
        if not should_initialize:
            return
        self.instance_id = str(uuid7())
        self.process_pool = Queue()
        self.pool_lock = Lock()
        self.releaser_executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel)
        self.stop_event = Event()
        self.pool_check_interval = int(os.getenv("PYTHON_CODE_EXECUTOR_POOL_CHECK_INTERVAL_IN_SECONDS", "3"))
        self.all_workers = {}
        self.all_workers_lock = Lock()
        self._shutting_down = False
        self.start_services()

    def start_services(self):
        logger.info(f"ProcessExecutor ({self.instance_id}): Registering signal handlers in PID {os.getpid()}.")
        if os.environ.get("WERKZEUG_RUN_MAIN") == "true":
            signal.signal(signal.SIGINT, self._handle_shutdown_signal)
            signal.signal(signal.SIGTERM, self._handle_shutdown_signal)

        self._pre_warm_process_pool()
        self._start_pool_monitor()

        logger.info(f"ProcessExecutor ({self.instance_id}): Services started.")

    def _handle_shutdown_signal(self, signum, frame):
        signal_name = signal.Signals(signum).name
        # Prevent re-entry if shutdown is already in progress
        if self.stop_event.is_set():
            logger.warning(
                f"ProcessExecutor ({self.instance_id}): PID {os.getpid()} received signal {signal_name}, but shutdown already in progress. Ignoring.")
            return

        logger.warning(
            f"ProcessExecutor ({self.instance_id}): PID {os.getpid()} received signal {signal_name}. Initiating graceful shutdown.")
        # Signal other parts of the executor to stop
        self.stop_event.set()

        # Deregister signal handlers to prevent re-entry during cleanup
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        signal.signal(signal.SIGTERM, signal.SIG_DFL)

        logger.warning(f"ProcessExecutor ({self.instance_id}): Starting cleanup...")
        self.cleanup()  # This should terminate and join all worker processes
        logger.warning(f"ProcessExecutor ({self.instance_id}): Cleanup finished.")

        # Unregister cleanup from atexit to prevent double execution, as we've called it directly.
        atexit.unregister(self.cleanup)

        logger.warning(
            f"ProcessExecutor ({self.instance_id}): Graceful cleanup finished. Exiting process via sys.exit(0).")
        sys.exit(0)  # Use sys.exit for standard shutdown

    def cleanup(self):
        logger.warning(f"ProcessExecutor ({self.instance_id}): Starting cleanup core logic...")

        if not self.stop_event.is_set():
            self.stop_event.set()

        schedule.clear()

        with self.all_workers_lock:
            workers_to_terminate = list(self.all_workers.values())

        logger.info(f"Terminating {len(workers_to_terminate)} worker processes.")
        futures = [self.releaser_executor.submit(self.terminate_worker, worker) for worker in workers_to_terminate]
        concurrent.futures.wait(futures)

        # Drain the queue to be safe
        while not self.process_pool.empty():
            try:
                self.process_pool.get_nowait()
            except Empty:
                break

        if self.releaser_executor:
            self.releaser_executor.shutdown(wait=True)

        logger.warning(f"ProcessExecutor ({self.instance_id}): Cleanup finished.")

    def terminate_worker(self, worker):
        worker_id = worker.get('id', 'unknown')
        process = worker.get('process')
        if not process:
            return

        # Close the connection if it exists and is open
        connection = worker.get('connection')
        if connection:
            try:
                connection.close()
            except Exception as e:
                logger.warning(f"Error closing connection for worker {worker_id}: {e}")

        if not process.is_alive():
            logger.info(f"Worker {worker_id} (PID: {process.pid if process.pid else 'N/A'}) already terminated.")
            with self.all_workers_lock:
                if worker_id in self.all_workers:
                    del self.all_workers[worker_id]
            return

        logger.info(f"Terminating worker {worker_id} (PID: {process.pid}).")
        try:
            process.terminate()  # Send SIGTERM
            process.join(timeout=2)  # Give it 2 seconds to die
            if process.is_alive():  # Check if it's still alive after timeout
                logger.warning(
                    f"Worker {worker_id} (PID: {process.pid}) did not terminate gracefully after SIGTERM, killing.")
                process.kill()  # Send SIGKILL
                process.join(timeout=1)  # Wait for SIGKILL to take effect
        except Exception as e:  # Catch any other exceptions during termination
            logger.error(f"Exception during termination of worker {worker_id} (PID: {process.pid}): {e}")
            if process.is_alive():
                process.kill()
                process.join(timeout=1)

        with self.all_workers_lock:
            if worker_id in self.all_workers:
                del self.all_workers[worker_id]

        logger.info(f"Termination sequence for worker {worker_id} complete.")

    # ... (rest of the methods like _start_pool_monitor, _check_pool, etc. are assumed to be here and correct)
    # For brevity, I will only include the methods that were significantly changed or are critical for context.

    def _start_pool_monitor(self):
        logger.info(f"Starting process pool monitor with {self.pool_check_interval} second interval")
        schedule.every(self.pool_check_interval).seconds.do(self._check_pool)
        self.releaser_executor.submit(self._run_scheduler)

    def _run_scheduler(self):
        logger.info("Starting scheduler for process pool monitoring")
        while not self.stop_event.is_set():
            schedule.run_pending()
            time.sleep(1)
        logger.info("Scheduler finished running")

    def _check_pool(self):
        if self.stop_event.is_set():
            return schedule.CancelJob
        self.ensure_pool_filled()

    def _pre_warm_process_pool(self):
        logger.info(f"Pre-warming process pool with {self.max_parallel} processes")
        futures = [self.releaser_executor.submit(self.create_worker_process) for _ in range(self.max_parallel)]
        concurrent.futures.wait(futures)

    def ensure_pool_filled(self):
        with self.all_workers_lock:  # Ensure thread-safe access to all_workers
            current_total_workers = len(self.all_workers)
            to_create = self.max_parallel - current_total_workers
            if to_create > 0:
                logger.info(
                    f"Pool needs {to_create} workers to reach max_parallel {self.max_parallel} (current total: {current_total_workers}). Creating...")
                for _ in range(to_create):
                    self.releaser_executor.submit(self.create_worker_process)

    def create_worker_process(self):
        start_time = time.time()
        worker_id = str(uuid.uuid4())[:8]
        parent_conn, child_conn = Pipe()
        process = None
        try:
            process = Process(target=process_worker.worker_process_main, args=(child_conn,))
            process.start()

            # Wait for READY signal from worker
            if parent_conn.poll(timeout=10):  # Wait up to 10 seconds for READY
                ready_signal = parent_conn.recv()
                if ready_signal != "READY":
                    raise Exception(f"Worker {worker_id} (PID: {process.pid}) sent unexpected signal: {ready_signal}")
            else:
                raise Exception(f"Worker {worker_id} (PID: {process.pid}) timed out waiting for READY signal.")

            worker = {'id': worker_id, 'process': process, 'connection': parent_conn}
            with self.all_workers_lock:
                self.all_workers[worker_id] = worker
            self.process_pool.put(worker)
            latency = _calculate_latency_ms(start_time)
            process_creation_histogram.record(latency)
            logger.info(f"Created worker {worker_id} (PID: {process.pid}) in {latency:.3f}ms.")
        except Exception as e:
            logger.error(
                f"Failed to create worker process {worker_id} (PID: {process.pid if process and process.pid else 'N/A'}): {e}")
            if process and process.is_alive():
                process.terminate()
                process.join(timeout=1)
            if parent_conn:
                parent_conn.close()
            if child_conn:
                child_conn.close()

    def get_worker(self):
        if self.stop_event.is_set():
            raise RuntimeError("Executor is shutting down")
        try:
            worker = self.process_pool.get(timeout=self.exec_timeout)
            if not worker['process'].is_alive():
                logger.warning(f"Got a dead worker {worker['id']} from pool. Terminating and retrying.")
                self.terminate_worker(worker)
                return self.get_worker()  # Retry
            return worker
        except Empty:
            logger.error("Timeout getting a worker from the pool.")
            raise RuntimeError("No available workers in the pool.")

    def run_scoring(self, code: str, data: dict) -> dict:
        if self.stop_event.is_set():
            return {"code": 503, "error": "Service is shutting down"}
        worker = None
        try:
            worker = self.get_worker()
            worker_id = worker.get('id', 'unknown')
            connection = worker.get('connection')
            if not connection:
                raise Exception(f"Worker {worker_id} has no connection object.")

            start_exec_time = time.time()
            connection.send({'code': code, 'data': data})

            # Wait for result with timeout
            if connection.poll(timeout=self.exec_timeout):  # exec_timeout should be defined, e.g., 60 seconds
                result = connection.recv()
            else:
                logger.error(f"Timeout waiting for result from worker {worker_id}")
                # Terminate the worker as it's unresponsive
                self.terminate_worker(worker)
                return {"code": 500, "error": "Execution timed out"}

            latency = _calculate_latency_ms(start_exec_time)
            process_execution_histogram.record(latency)

            self.process_pool.put(worker)  # Return worker to pool
            return result
        except Exception as e:
            logger.error(f"Error in run_scoring with worker {worker.get('id') if worker else 'N/A'}: {e}")
            if worker:
                self.terminate_worker(worker)  # Terminate failed worker
            return {"code": 500, "error": f"Failed to execute code: {e}"}
