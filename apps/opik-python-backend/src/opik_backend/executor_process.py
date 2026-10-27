import atexit
import concurrent.futures
import json
import logging
import os
import secrets
import signal
import time
import uuid
import sys
import multiprocessing
from queue import Queue, Empty
from threading import Event, Lock, Thread
from multiprocessing.connection import Connection
from typing import Any, Optional, Tuple, Union


from opentelemetry import metrics

from opik_backend.executor import (
    CodeExecutorBase,
    EXEC_TIMEOUT_ERROR,
    SATURATED_ERROR,
    SHUTDOWN_ERROR,
)
from opik_backend import process_worker

logger = logging.getLogger(__name__)

# Python 3.14 made "forkserver" the Linux default. A forkserver worker is a fresh
# interpreter that must re-import opik before it can send READY, which on a loaded
# host exceeds the READY timeout, leaving the pool empty and every scoring call a 503.
# A forked worker inherits the parent's already-imported modules. Linux only: macOS
# system frameworks are not fork-safe, which is why "spawn" stays its default.
_mp_context = multiprocessing.get_context("fork" if sys.platform == "linux" else None)

# filelock refuses os.fork while another thread is mid-fork, and the pool pre-warms its
# workers from several threads at once, so forks are serialised.
_fork_lock = Lock()

PROCESS_NETWORK_ISOLATION_ENV_VAR = "PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION"

MAX_REPLY_BYTES_ENV_VAR = "PYTHON_CODE_EXECUTOR_MAX_RESULT_SIZE_IN_BYTES"
READY_TIMEOUT_SECONDS = 10


def max_reply_bytes() -> int:
    """A positive integer, so a bad value fails startup instead of refusing every reply or lifting the cap."""
    value = os.getenv(MAX_REPLY_BYTES_ENV_VAR) or str(64 * 1024 * 1024)
    if not value.isdecimal() or int(value) == 0:
        raise ValueError(f"{MAX_REPLY_BYTES_ENV_VAR} must be a positive integer, not {value!r}")
    return int(value)


# Far above any real score list; a longer reply is refused before it is read, so it is never buffered.
MAX_REPLY_BYTES = max_reply_bytes()
MALFORMED_REPLY_ERROR = "Execution failed: the worker returned a malformed or oversized result"
# A worker holding more pending replies than this is replaced rather than drained.
_MAX_PENDING_REPLIES = 16


class _IsolationUnavailable(Exception):
    pass


class _IsolationFailureLog:
    """Every worker of one executor fails the same way, so the cause is logged once per executor."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._logged = False

    def log(self, reason: str) -> None:
        with self._lock:
            if self._logged:
                return
            self._logged = True
        logger.error(
            f"{PROCESS_NETWORK_ISOLATION_ENV_VAR}=true but process workers cannot be isolated ({reason}). "
            f"It needs Linux, with python-backend running as root (CAP_SETUID and CAP_SETGID, granted by default). "
            f"No process worker will start until then."
        )


# Studio's isolated workers have no executor; within a Studio job subprocess this logs once per job.
_one_shot_isolation_failures = _IsolationFailureLog()

# Studio's isolated workers, reused across metric calls of the job instead of a fork per call.
_idle_isolated_workers: "Queue[Tuple[multiprocessing.Process, Connection]]" = Queue()
# Workers are forked from this long-lived thread: their parent-death signal fires when the forking thread
# exits, so a worker forked from a short-lived evaluation thread would die with it.
_isolated_worker_spawner = concurrent.futures.ThreadPoolExecutor(max_workers=1)

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


def terminate_worker(worker):
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
        return

    logger.info(f"Terminating worker {worker_id} (PID: {process.pid}).")
    try:
        process.terminate()  # Send SIGTERM
        if not _wait_for_exit(process, 2):
            logger.warning(
                f"Worker {worker_id} (PID: {process.pid}) did not terminate gracefully after SIGTERM, killing.")
            process.kill()  # Send SIGKILL
            _wait_for_exit(process, 1)  # Wait for SIGKILL to take effect
    except Exception as e:  # Catch any other exceptions during termination
        logger.error(f"Exception during termination of worker {worker_id} (PID: {process.pid}): {e}")
        if process.is_alive():
            process.kill()
            _wait_for_exit(process, 1)

    logger.info(f"Termination sequence for worker {worker_id} complete.")


def isolation_enabled() -> bool:
    """Opt-in, so installs that run python-backend as non-root keep working unchanged.

    Any value other than true/false (any case) or empty raises, so a typo fails startup
    instead of leaving workers unisolated.
    """
    value = os.getenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, "")
    if value.lower() in ("", "false"):
        return False
    if value.lower() == "true":
        return True
    raise ValueError(f"{PROCESS_NETWORK_ISOLATION_ENV_VAR} must be true or false, not {value!r}")


def _start(process: multiprocessing.Process) -> None:
    with _fork_lock:
        process.start()


def _wait_for_exit(process: multiprocessing.Process, timeout: float) -> bool:
    """Polls rather than join(timeout): join waits on a pipe the worker can close, and then blocks unbounded."""
    deadline = time.monotonic() + timeout
    while process.is_alive():
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.01)
    return True


def _read_reply(connection: Connection) -> Any:
    """One worker reply, decoded as JSON: replies are read as data, not objects.

    None when the reply is too long or not JSON. EOFError still propagates.
    """
    try:
        return json.loads(connection.recv_bytes(MAX_REPLY_BYTES))
    except (OSError, ValueError):
        return None


def _command(code: str, data: dict, payload_type: Optional[str]) -> Tuple[str, dict]:
    """A command and its request id, new for every request so no earlier reply can match it."""
    request_id = secrets.token_hex(16)
    return request_id, {'code': code, 'data': data, 'payload_type': payload_type,
                        process_worker.REQUEST_ID_KEY: request_id}


def _read_result(connection: Connection, request_id: str) -> Optional[dict]:
    """The result of the command with this request id; None for any other reply."""
    reply = _read_reply(connection)
    if (
        isinstance(reply, dict)
        and reply.get(process_worker.REQUEST_ID_KEY) == request_id
        and isinstance(reply.get(process_worker.RESULT_KEY), dict)
    ):
        return reply[process_worker.RESULT_KEY]
    return None


def _drain(connection: Connection) -> bool:
    """Discards replies left on the pipe since the last request; False when the worker can't be reused."""
    try:
        for _ in range(_MAX_PENDING_REPLIES):
            if not connection.poll(0):
                return True
            connection.recv_bytes(MAX_REPLY_BYTES)
        return not connection.poll(0)
    except (OSError, EOFError):
        return False


def _isolation_error(reply: Any) -> Optional[str]:
    if isinstance(reply, dict) and isinstance(reply.get(process_worker.ISOLATION_ERROR_KEY), str):
        return reply[process_worker.ISOLATION_ERROR_KEY]
    return None


def _stop_isolated_worker(process: multiprocessing.Process, connection: Connection) -> None:
    # Closing the pipe ends the worker's command loop.
    connection.close()
    if process.pid is not None and not _wait_for_exit(process, 1):
        process.kill()
        process.join()


def _start_isolated_worker() -> Union[dict, Tuple[multiprocessing.Process, Connection]]:
    """A READY isolated worker, or the error reply when it could not start."""
    parent_conn, child_conn = _mp_context.Pipe()
    # Not daemonic, so metrics can start processes of their own. The job's exit still ends idle workers:
    # their parent-death signal fires when the spawner thread stops, before multiprocessing joins them.
    process = _mp_context.Process(target=process_worker.worker_process_main, args=(child_conn, True))
    ready = None
    try:
        _start(process)
        # Only the worker holds its end now, so a worker that dies surfaces as EOFError.
        child_conn.close()
        if not parent_conn.poll(READY_TIMEOUT_SECONDS):
            return {"code": 500, "error": "Isolated worker timed out waiting for READY"}
        ready = _read_reply(parent_conn)
        if ready == "READY":
            return process, parent_conn
        reason = _isolation_error(ready)
        if reason is None:
            return {"code": 500, "error": "Isolated worker sent a malformed ready reply"}
        _one_shot_isolation_failures.log(reason)
        return ready
    except EOFError:
        return {"code": 500, "error": "Isolated worker exited before returning a result"}
    finally:
        child_conn.close()
        if ready != "READY":
            _stop_isolated_worker(process, parent_conn)


def run_user_code_in_isolated_worker(code: str, data: dict, payload_type: Optional[str] = None) -> dict:
    """Run user code in an isolated worker.

    For callers without a worker pool: Optimization Studio runs code metrics inside its
    job subprocess, which needs the network itself and so cannot be isolated as a whole.
    Workers are reused within that process, like pool workers, and replaced after any
    failure. No timeout here, as before: the job supervisor bounds the run, and isolated
    workers are killed when the job process exits.
    """
    worker = None
    while worker is None:
        try:
            worker = _idle_isolated_workers.get_nowait()
        except Empty:
            started = _isolated_worker_spawner.submit(_start_isolated_worker).result()
            if isinstance(started, dict):
                return started
            worker = started
        if not worker[0].is_alive() or not _drain(worker[1]):
            _stop_isolated_worker(*worker)
            worker = None
    process, connection = worker
    reusable = False
    try:
        request_id, command = _command(code, data, payload_type)
        connection.send(command)
        result = _read_result(connection, request_id)
        if result is None:
            return {"code": 400, "error": MALFORMED_REPLY_ERROR}
        reusable = True
        return result
    except (EOFError, BrokenPipeError):
        return {"code": 500, "error": "Isolated worker exited before returning a result"}
    finally:
        if reusable:
            _idle_isolated_workers.put(worker)
        else:
            _stop_isolated_worker(process, connection)


class ProcessExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        self.process_pool = Queue()
        self.releaser_executor = None
        self.stop_event = Event()
        self.pool_check_interval = int(os.getenv("PYTHON_CODE_EXECUTOR_POOL_CHECK_INTERVAL_IN_SECONDS", "3"))
        self.isolate = isolation_enabled()
        self._isolation_failures = _IsolationFailureLog()

        self.scheduler_thread = None

    def start_services(self):
        logger.info("ProcessExecutor: Registering signal handlers")
        signal.signal(signal.SIGINT, self._handle_shutdown_signal)
        signal.signal(signal.SIGTERM, self._handle_shutdown_signal)

        self.releaser_executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel)
        self._pre_warm_process_pool()

        logger.info("Starting process pool monitor.")
        self.scheduler_thread = Thread(target=self._pool_monitor_loop, daemon=True)
        self.scheduler_thread.start()

        logger.info(f"ProcessExecutor: Services started (network_isolated={self.isolate}).")

    def _handle_shutdown_signal(self, signum, frame):
        signal_name = signal.Signals(signum).name
        # Prevent re-entry if shutdown is already in progress
        if self.stop_event.is_set():
            logger.warning(
                f"ProcessExecutor: received signal {signal_name}, but shutdown already in progress. Ignoring.")
            return

        logger.warning(
            f"ProcessExecutor: received signal {signal_name}. Initiating graceful shutdown.")
        # Signal other parts of the executor to stop
        self.stop_event.set()

        # Deregister signal handlers to prevent re-entry during cleanup
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        signal.signal(signal.SIGTERM, signal.SIG_DFL)

        logger.warning(f"ProcessExecutor: Starting cleanup...")
        self.cleanup()  # This should terminate and join all worker processes
        logger.warning(f"ProcessExecutor: Cleanup finished.")

        # Unregister cleanup from atexit to prevent double execution, as we've called it directly.
        atexit.unregister(self.cleanup)

        logger.warning(
            f"ProcessExecutor: Graceful cleanup finished. Exiting process via sys.exit(0).")
        sys.exit(0)  # Use sys.exit for standard shutdown

    def cleanup(self):
        logger.warning(f"ProcessExecutor: Starting cleanup core logic...")

        if not self.stop_event.is_set():
            self.stop_event.set()

        if self.scheduler_thread:
            self.scheduler_thread.join()

        # Drain all workers from the pool for termination
        workers_to_terminate = []
        while not self.process_pool.empty():
            try:
                workers_to_terminate.append(self.process_pool.get_nowait())
            except Empty:
                break

        logger.info(f"Terminating {len(workers_to_terminate)} worker processes.")
        futures = [self.releaser_executor.submit(terminate_worker, worker) for worker in workers_to_terminate]
        concurrent.futures.wait(futures)

        # Drain the queue to be safe
        while not self.process_pool.empty():
            try:
                self.process_pool.get_nowait()
            except Empty:
                break

        if self.releaser_executor:
            self.releaser_executor.shutdown(wait=True)

        logger.warning(f"ProcessExecutor: Cleanup finished.")

    def _pool_monitor_loop(self):
        """
        Periodically checks the pool and tops it up with new workers if needed.
        This loop is designed to run in a daemon thread.
        """
        logger.info("Starting process pool monitor loop.")
        # The loop will wait for 'pool_check_interval' seconds, but will exit
        # immediately if 'stop_event' is set during the wait.
        while not self.stop_event.wait(self.pool_check_interval):
            try:
                self.ensure_pool_filled()
            except Exception as e:
                logger.error(f"Error in pool monitor loop: {e}", exc_info=True)
        logger.info("Process pool monitor loop finished.")

    def _pre_warm_process_pool(self):
        logger.info(f"Pre-warming process pool with {self.max_parallel} processes")
        futures = [self.releaser_executor.submit(self.create_worker_process) for _ in range(self.max_parallel)]
        concurrent.futures.wait(futures)

    def ensure_pool_filled(self):
        if self.stop_event.is_set():
            logger.debug("ProcessExecutor: entered 'ensure_pool_filled' while shutdown in progress. Ignoring.")
            return

        # Check how many workers are in the process_pool
        temp_workers = []
        while not self.process_pool.empty():
            try:
                temp_workers.append(self.process_pool.get_nowait())
            except Empty:
                break
        current_total_workers = len(temp_workers)
        # Return workers to the pool
        for w in temp_workers:
            self.process_pool.put(w)
        to_create = self.max_parallel - current_total_workers
        if to_create > 0:
            logger.info(
                f"Pool needs {to_create} workers to reach max_parallel {self.max_parallel} (current total: {current_total_workers}). Creating...")
            for _ in range(to_create):
                self.releaser_executor.submit(self.create_worker_process)

    def create_worker_process(self):
        if self.stop_event.is_set():
            logger.debug("ProcessExecutor: entered 'create_worker_process' while shutdown in progress. Ignoring.")
            return

        start_time = time.time()
        worker_id = str(uuid.uuid4())[:8]
        parent_conn, child_conn = _mp_context.Pipe()
        process = None
        try:
            process = _mp_context.Process(target=process_worker.worker_process_main, args=(child_conn, self.isolate))
            _start(process)

            # Wait for READY signal from worker
            if parent_conn.poll(timeout=READY_TIMEOUT_SECONDS):
                ready_signal = _read_reply(parent_conn)
                reason = _isolation_error(ready_signal)
                if reason is not None:
                    raise _IsolationUnavailable(reason)
                if ready_signal != "READY":
                    raise Exception(f"Worker {worker_id} (PID: {process.pid}) sent unexpected signal: {ready_signal}")
            else:
                raise Exception(f"Worker {worker_id} (PID: {process.pid}) timed out waiting for READY signal.")

            worker = {'id': worker_id, 'process': process, 'connection': parent_conn}
            self.process_pool.put(worker)
            self._update_pool_size_metric()
            latency = _calculate_latency_ms(start_time)
            process_creation_histogram.record(latency)
            logger.info(f"Created worker {worker_id} (PID: {process.pid}) in {latency:.3f}ms.")
        except Exception as e:
            if isinstance(e, _IsolationUnavailable):
                self._isolation_failures.log(str(e))
            else:
                logger.error(
                    f"Failed to create worker process {worker_id} (PID: {process.pid if process and process.pid else 'N/A'}): {e}")
            if process and process.is_alive():
                process.terminate()
                _wait_for_exit(process, 1)
            if parent_conn:
                parent_conn.close()
            if child_conn:
                child_conn.close()

    def _update_pool_size_metric(self):
        process_pool_size_gauge.set(self.process_pool.qsize())

    def get_worker(self):
        """Acquire a worker from the pool.

        Single-pass: one queue read and one liveness check. Raises
        :class:`TimeoutError` on saturation, executor shutdown, or when the
        retrieved worker is dead. Dead-worker cleanup is dispatched
        asynchronously so the request thread isn't held on the
        SIGTERM/SIGKILL handshake; the caller retries via the same 503
        path as saturation. ``run_scoring`` translates this into HTTP 503.
        """
        if self.stop_event.is_set():
            raise TimeoutError(SHUTDOWN_ERROR)
        self._update_pool_size_metric()
        try:
            worker = self.process_pool.get(timeout=self.pool_acquire_timeout)
        except Empty as e:
            # Detailed diagnostic stays in the log; the exception uses
            # the wire-facing constant so internal config doesn't leak
            # to downstream `str(exc)` consumers.
            logger.warning(
                f"Process pool exhausted: no worker available within "
                f"{self.pool_acquire_timeout:.3f}s (max_parallel={self.max_parallel})"
            )
            # Refresh the gauge so the saturation event reports the
            # zero-available state, not the pre-call value.
            self._update_pool_size_metric()
            raise TimeoutError(SATURATED_ERROR) from e
        self._update_pool_size_metric()
        alive = worker['process'].is_alive()
        if not alive or not _drain(worker['connection']):
            state = "Dead worker" if not alive else "Worker with undiscardable pending replies"
            logger.warning(
                f"{state} {worker['id']} retrieved from pool; "
                f"terminating asynchronously and surfacing as saturation"
            )
            self._async_terminate(worker)
            raise TimeoutError(SATURATED_ERROR)
        return worker

    def _async_terminate(self, worker):
        """Schedule worker termination off the request thread.

        Falls back to inline termination when the releaser pool isn't up —
        ``start_services`` hasn't run, e.g. in test fixtures or pre-init —
        where the request thread isn't on the line and inline is fine.
        """
        if self.releaser_executor is not None:
            self.releaser_executor.submit(terminate_worker, worker)
        else:
            terminate_worker(worker)

    def run_scoring(self, code: str, data: dict, payload_type: Optional[str] = None) -> dict:
        if self.stop_event.is_set():
            return {"code": 503, "error": SHUTDOWN_ERROR}
        try:
            worker = self.get_worker()
        except TimeoutError:
            # Re-check stop_event: a SIGTERM/SIGINT during the bounded
            # Queue.get races into the TimeoutError branch, in which case
            # the failure is really shutdown, not pool saturation. Report
            # it as the former so monitoring doesn't false-positive on
            # saturation.
            if self.stop_event.is_set():
                return {"code": 503, "error": SHUTDOWN_ERROR}
            return {"code": 503, "error": SATURATED_ERROR}
        try:
            worker_id = worker.get('id', 'unknown')
            connection = worker.get('connection')
            if not connection:
                raise Exception(f"Worker {worker_id} has no connection object.")

            start_exec_time = time.time()
            request_id, command = _command(code, data, payload_type)
            connection.send(command)

            # Wait for result with timeout
            if connection.poll(timeout=self.exec_timeout):
                result = _read_result(connection, request_id)
                if result is None:
                    # The pipe may hold more of the bad reply, so the worker is not reused.
                    self._async_terminate(worker)
                    return {"code": 400, "error": MALFORMED_REPLY_ERROR}
            else:
                logger.error(f"Timeout waiting for result from worker {worker_id}")
                # Dispatch termination off the request thread so the
                # SIGTERM/SIGKILL handshake doesn't block the caller.
                self._async_terminate(worker)
                # 504 (not 500) so Java BE's retry policy treats per-execution
                # timeouts uniformly with the Docker executor.
                return {"code": 504, "error": EXEC_TIMEOUT_ERROR}

            latency = _calculate_latency_ms(start_exec_time)
            process_execution_histogram.record(latency)

            self.process_pool.put(worker)  # Return worker to pool
            self._update_pool_size_metric()
            return result
        except Exception as e:
            logger.error(f"Error in run_scoring with worker {worker.get('id', 'unknown')}: {e}")
            # Dispatch termination off the request thread so the
            # SIGTERM/SIGKILL handshake doesn't block the caller.
            self._async_terminate(worker)
            return {"code": 500, "error": f"Failed to execute code: {e}"}
