import atexit
import concurrent.futures
import errno
import functools
import json
import logging
import math
import os
import secrets
import signal
import socket
import struct
import time
import uuid
import sys
import multiprocessing
from queue import Queue, Empty
from threading import Event, Lock, Thread
from multiprocessing.connection import Connection, wait
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

# Off the isolation path a forked worker inherits the parent's already-imported modules, so it
# sends READY without re-importing opik (a fresh interpreter would re-import and, on a loaded host,
# exceed the READY timeout, leaving the pool empty and every call a 503). Linux only: macOS system
# frameworks are not fork-safe, which is why "spawn" stays its default.
_mp_context = multiprocessing.get_context("fork" if sys.platform == "linux" else None)

# On the isolation path a worker forks from a forkserver template instead of from the backend, so it
# starts from the template's memory and not the backend's per-request state. The template is started
# before that state exists (see start_isolation_template) and preloads what user code imports, so a
# forked worker is still ready without re-importing. Off Linux isolation never runs, so this is unused.
_isolated_context = multiprocessing.get_context("forkserver") if sys.platform == "linux" else _mp_context
# Preloads process_worker once so each fork is cheap; isolated_preload first installs the light metric
# import stubs, so the template imports the stub layer rather than the full opik.
_ISOLATION_PRELOAD = ["opik_backend.isolated_preload"]

# filelock refuses os.fork while another thread is mid-fork, and the pool pre-warms its
# workers from several threads at once, so forks are serialised.
_fork_lock = Lock()


def start_isolation_template() -> None:
    """Start the forkserver template now, so isolated workers fork from it, not from the backend.

    Idempotent (the forkserver is a singleton). Call it before the backend loads per-request state —
    pool startup, and the top of the Studio job — so that state is never in the template's memory.
    No-op off Linux, where isolation does not run.
    """
    if sys.platform != "linux":
        return
    import _posixsubprocess
    import tempfile
    from multiprocessing import forkserver, util

    # Only what workers keep, plus the import path; HOME as in the workers.
    keys = process_worker.ISOLATED_ENV_KEYS + ("PYTHONPATH",)
    env = [f"{k}={os.environ[k]}".encode() for k in keys if k in os.environ]
    env.append(f"HOME={tempfile.gettempdir()}".encode())

    def spawn_with_env(path: str, args: list, passfds: Any) -> int:
        # Python 3.14's util.spawnv_passfds with an explicit environment instead of the inherited one;
        # fork_exec's arguments change between versions (a test pins the version).
        passfds = tuple(sorted(map(int, passfds)))
        errpipe_read, errpipe_write = os.pipe()
        try:
            return _posixsubprocess.fork_exec(
                args, [path], True, passfds, None, env,
                -1, -1, -1, -1, -1, -1, errpipe_read, errpipe_write,
                False, False, -1, None, None, None, -1, None)
        finally:
            os.close(errpipe_read)
            os.close(errpipe_write)

    # The forkserver has no environment parameter, so its launcher is swapped for this call only; os.environ
    # is left as is. Under the fork lock, so no other fork or spawn in this process uses the swapped launcher.
    forkserver.set_forkserver_preload(_ISOLATION_PRELOAD)
    with _fork_lock:
        original = util.spawnv_passfds
        util.spawnv_passfds = spawn_with_env
        try:
            forkserver.ensure_running()
        finally:
            util.spawnv_passfds = original


def _worker_context(isolate: bool):
    return _isolated_context if isolate else _mp_context

PROCESS_NETWORK_ISOLATION_ENV_VAR = "PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION"

MAX_REPLY_BYTES_ENV_VAR = "PYTHON_CODE_EXECUTOR_MAX_RESULT_SIZE_IN_BYTES"
READY_TIMEOUT_ENV_VAR = "PYTHON_CODE_EXECUTOR_READY_TIMEOUT_SECONDS"


def max_reply_bytes() -> int:
    """The largest reply read from a worker; a longer one is refused before its body is read, so it is never buffered.

    The default is far above any real score list. A positive integer, so a bad value fails startup instead of
    refusing every reply or lifting the cap. Read where workers are used, not at import: a Studio job imports
    this module whatever the strategy.
    """
    value = os.getenv(MAX_REPLY_BYTES_ENV_VAR) or str(64 * 1024 * 1024)
    if not value.isdecimal() or int(value) == 0:
        # only an excerpt of the value: it goes to the startup log
        raise ValueError(f"{MAX_REPLY_BYTES_ENV_VAR} must be a positive integer, not {value[:32]!r}")
    return int(value)


def ready_timeout_seconds() -> float:
    """How long a new worker may take to report READY; longer and it is stopped and not added to the pool.

    A positive finite number, so a bad value fails startup instead of failing every worker start or never
    bounding it. Read where workers are used, as max_reply_bytes is.
    """
    value = os.getenv(READY_TIMEOUT_ENV_VAR) or "10"
    try:
        seconds = float(value)
    except ValueError:
        seconds = math.nan
    if not math.isfinite(seconds) or seconds <= 0:
        # only an excerpt of the value: it goes to the startup log
        raise ValueError(f"{READY_TIMEOUT_ENV_VAR} must be a positive number of seconds, not {value[:32]!r}")
    return seconds


MALFORMED_REPLY_ERROR = "Execution failed: the worker returned a malformed or oversized result"
# A worker holding more pending replies than this is replaced rather than drained.
_MAX_PENDING_REPLIES = 16
# Stopping a worker never waits longer than these. SIGTERM ends a worker at once (its handler is the default),
# so the first only covers a worker whose user code ignores it; SIGKILL can't be ignored, so the second is only
# the kernel delivering it. Not settings: neither depends on the workload.
_TERMINATE_GRACE_SECONDS = 2
_KILL_GRACE_SECONDS = 1
# A worker writes a reply in one go once it has encoded it, so a reply that has started and then gets no more
# bytes for this long is from a worker that stalled mid-message. Outside a scoring call, which has its own deadline,
# it bounds each pause rather than the whole reply, so a large reply that keeps arriving completes. Draining uses it
# as a fixed bound: pending replies are discarded, and a worker that can't be drained in time is just replaced.
_REPLY_GRACE_SECONDS = 1


class _StalledReply(Exception):
    """The worker sent part of a reply and stopped before the deadline; the pipe is mid-message."""


class _IsolationUnavailable(Exception):
    pass


# The interpreter the template launcher's copied fork_exec call was written for.
_TEMPLATE_PYTHON = (3, 14)


def _python_version() -> Tuple[int, int]:
    return sys.version_info[:2]


def _start_template() -> None:
    """start_isolation_template, with any failure raised as _IsolationUnavailable naming the step."""
    if sys.platform == "linux" and _python_version() != _TEMPLATE_PYTHON:
        # The copied fork_exec call takes another argument list elsewhere, so refuse rather than guess.
        version = ".".join(map(str, _python_version()))
        raise _IsolationUnavailable(
            f"check interpreter failed: needs Python {_TEMPLATE_PYTHON[0]}.{_TEMPLATE_PYTHON[1]}, not {version}"
        )
    try:
        start_isolation_template()
    except Exception as e:
        raise _IsolationUnavailable(f"start worker template failed: {e}") from e


class _IsolationFailureLog:
    """Every worker of one executor fails the same way, so the cause is logged once per executor."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._logged = False

    def log(self, reason: str, cause: Optional[BaseException] = None) -> None:
        with self._lock:
            if self._logged:
                return
            self._logged = True
        # cause, when this process raised it, adds its traceback to the same line.
        logger.error(
            f"{PROCESS_NETWORK_ISOLATION_ENV_VAR}=true but process workers cannot be isolated ({reason}). "
            f"It needs Linux and Python {_TEMPLATE_PYTHON[0]}.{_TEMPLATE_PYTHON[1]}, with python-backend running as "
            f"root (CAP_SETUID and CAP_SETGID, granted by default). No process worker will start until then.",
            exc_info=cause,
        )


# Studio's isolated workers have no executor; within a Studio job subprocess this logs once per job.
_one_shot_isolation_failures = _IsolationFailureLog()

# Studio's isolated workers, reused across metric calls of the job instead of a fork per call.
_idle_isolated_workers: "Queue[Tuple[multiprocessing.Process, Connection]]" = Queue()
# Forks are serialised on one thread, matching the pool and keeping filelock's one-fork-at-a-time rule.
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
        _close(connection, worker_id)

    if not process.is_alive():
        logger.info(f"Worker {worker_id} (PID: {process.pid if process.pid else 'N/A'}) already terminated.")
        return

    logger.info(f"Terminating worker {worker_id} (PID: {process.pid}).")
    _stop_process(process, worker_id)
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
    """True once the process has exited, False at the deadline; never waits past it.

    Not join(timeout): when the worker has closed its end of the sentinel pipe, join returns from the sentinel at
    once and then waits on waitpid unbounded. This blocks on the sentinel up to the deadline instead; a sentinel
    that is readable while the process lives was closed by the worker, and only then does it sleep between checks.
    """
    deadline = time.monotonic() + timeout
    pause = 0.01
    while process.is_alive():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        if wait([process.sentinel], remaining):
            time.sleep(min(pause, remaining))
            pause = min(pause * 2, 0.2)
    return True


def _signal(process: multiprocessing.Process, signum: int, worker_id: str) -> None:
    """os.kill rather than terminate()/kill(), which wait on the sentinel and then on waitpid when the signal is refused."""
    if not process.is_alive():
        return
    try:
        os.kill(process.pid, signum)
    except ProcessLookupError:
        pass  # exited since the check
    except OSError as e:
        logger.error(f"Could not send {signal.Signals(signum).name} to worker {worker_id} (PID: {process.pid}): {e}")


def _stop_process(process: multiprocessing.Process, worker_id: str) -> None:
    """SIGTERM, then SIGKILL; returns within the two grace periods whatever the worker does."""
    _signal(process, signal.SIGTERM, worker_id)
    if _wait_for_exit(process, _TERMINATE_GRACE_SECONDS):
        return
    logger.warning(f"Worker {worker_id} (PID: {process.pid}) did not terminate gracefully after SIGTERM, killing.")
    _signal(process, signal.SIGKILL, worker_id)
    if not _wait_for_exit(process, _KILL_GRACE_SECONDS):
        logger.error(f"Worker {worker_id} (PID: {process.pid}) still running after SIGKILL; leaving it.")


def _close(connection: Connection, worker_id: str) -> None:
    try:
        connection.close()
    except OSError as e:
        logger.warning(f"Error closing connection for worker {worker_id}: {e}")


def _recv_exact(sock: socket.socket, size: int, deadline: float, idle: float) -> bytearray:
    buf = bytearray(size)
    with memoryview(buf) as view:
        received = 0
        while received < size:
            try:
                # MSG_DONTWAIT, so bytes already on the pipe cost one recv, as with os.read, and only a read
                # that would block waits, never past the deadline.
                n = sock.recv_into(view[received:], size - received, socket.MSG_DONTWAIT)
            except BlockingIOError:
                # Each time the pipe runs dry it waits at most `idle`, and never past the deadline: bytes that keep
                # arriving restart the wait, and a pause of `idle` is a stall.
                timeout = min(deadline - time.monotonic(), idle)
                if timeout <= 0 or not wait([sock], timeout):
                    raise _StalledReply("the worker sent part of a reply and stalled")
                continue
            if n == 0:
                raise EOFError
            received += n
    return buf


def _recv_reply(connection: Connection, deadline: float, max_bytes: int, idle: float = math.inf) -> Optional[bytearray]:
    """One message as Connection.recv_bytes frames it, read without blocking past the deadline or pausing past idle.

    None when it is longer than max_bytes, before its body is read. Raises _StalledReply at the deadline or after
    a pause of idle, and EOFError when the worker closed the pipe.
    """
    # The pipe's own descriptor (Pipe() is a Unix socket pair), detached after the read: family, type and protocol are
    # given, so building it queries nothing. A process-wide default timeout would have made the descriptor
    # non-blocking, which Connection.send can't handle, so that is undone.
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM, 0, connection.fileno())
    try:
        if sock.gettimeout() is not None:
            sock.settimeout(None)
        size, = struct.unpack("!i", _recv_exact(sock, 4, deadline, idle))
        if size == -1:
            size, = struct.unpack("!Q", _recv_exact(sock, 8, deadline, idle))
        if size > max_bytes:
            return None
        return _recv_exact(sock, size, deadline, idle)
    finally:
        sock.detach()


def _read_reply(connection: Connection, deadline: float, max_bytes: int, idle: float = math.inf) -> Any:
    """One worker reply, decoded as JSON: replies are read as data, not objects.

    None when the reply is too long, not JSON or could not be read. EOFError and _StalledReply still propagate.
    """
    try:
        reply = _recv_reply(connection, deadline, max_bytes, idle)
    except OSError as e:
        # The pipe itself failed; the size limit is checked on the length prefix and never raises.
        code = errno.errorcode.get(e.errno, e.errno) if e.errno is not None else "none"
        logger.warning(f"Discarded a worker reply after a connection/read error: {type(e).__name__} (errno {code})")
        return None
    if reply is None:
        logger.warning(f"Discarded a worker reply longer than {max_bytes} bytes")
        return None
    try:
        return json.loads(reply)
    except ValueError as e:
        # Only the type: a decode error's text quotes bytes of the reply.
        logger.warning(f"Discarded a worker reply that is not JSON: {type(e).__name__}")
        return None


def _command(code: str, data: dict, payload_type: Optional[str]) -> Tuple[str, dict]:
    """A command and its request id, new for every request so no earlier reply can match it."""
    request_id = secrets.token_hex(16)
    return request_id, {'code': code, 'data': data, 'payload_type': payload_type,
                        process_worker.REQUEST_ID_KEY: request_id}


def _read_result(connection: Connection, request_id: str, deadline: float, max_bytes: int,
                 idle: float = math.inf) -> Optional[dict]:
    """The result of the command with this request id; None for any other reply."""
    reply = _read_reply(connection, deadline, max_bytes, idle)
    if (
        isinstance(reply, dict)
        and reply.get(process_worker.REQUEST_ID_KEY) == request_id
        and isinstance(reply.get(process_worker.RESULT_KEY), dict)
    ):
        return reply[process_worker.RESULT_KEY]
    return None


def _drain(connection: Connection, max_bytes: int) -> bool:
    """Discards replies left on the pipe since the last request; False when the worker can't be reused."""
    deadline = time.monotonic() + _REPLY_GRACE_SECONDS
    try:
        for _ in range(_MAX_PENDING_REPLIES):
            if not connection.poll(0):
                return True
            if _recv_reply(connection, deadline, max_bytes) is None:
                logger.warning(f"Could not discard a worker's pending replies: one is longer than {max_bytes} bytes")
                return False
        return not connection.poll(0)
    except (OSError, EOFError, _StalledReply) as e:
        logger.warning(f"Could not discard a worker's pending replies: {type(e).__name__}")
        return False


def _isolation_error(reply: Any) -> Optional[str]:
    if isinstance(reply, dict) and isinstance(reply.get(process_worker.ISOLATION_ERROR_KEY), str):
        return reply[process_worker.ISOLATION_ERROR_KEY]
    return None


def _stop_isolated_worker(process: multiprocessing.Process, connection: Connection) -> None:
    _close(connection, "isolated")
    _stop_process(process, "isolated")


@atexit.register
def _stop_idle_isolated_workers() -> None:
    # Isolated workers are not daemonic, so a metric can start its own processes. Stop the idle ones at
    # exit before multiprocessing's own atexit joins them, which would otherwise block on a live worker.
    # Nothing returns a worker by then (threads are joined before atexit), so the count is final.
    for _ in range(_idle_isolated_workers.qsize()):
        try:
            _stop_isolated_worker(*_idle_isolated_workers.get_nowait())
        except Empty:
            return


def start_job_isolation_template() -> None:
    """Start the template for a Studio job, logging a failure once instead of raising.

    Only code metrics need it, so the job runs on; each code metric then returns the same named error.
    """
    try:
        _start_template()
    except _IsolationUnavailable as e:
        _one_shot_isolation_failures.log(str(e), e.__cause__)


def _start_isolated_worker(ready_timeout: float, max_bytes: int) -> Union[dict, Tuple[multiprocessing.Process, Connection]]:
    """A READY isolated worker, or the error reply when it could not start."""
    # Idempotent; ensures the template was started with the reduced environment, not lazily by the fork.
    try:
        _start_template()
    except _IsolationUnavailable as e:
        _one_shot_isolation_failures.log(str(e), e.__cause__)
        return {"code": 500, "error": f"Worker isolation failed: {e}", process_worker.ISOLATION_ERROR_KEY: str(e)}
    parent_conn, child_conn = _isolated_context.Pipe()
    # Not daemonic, so metrics can start processes of their own. Idle workers are ended at the job's exit
    # by _stop_idle_isolated_workers; the template's death is a backstop through the parent-death signal.
    process = _isolated_context.Process(target=process_worker.worker_process_main, args=(child_conn, True))
    ready = None
    try:
        deadline = time.monotonic() + ready_timeout
        _start(process)
        # Only the worker holds its end now, so a worker that dies surfaces as EOFError.
        child_conn.close()
        if not parent_conn.poll(ready_timeout):
            return {"code": 500, "error": "Isolated worker timed out waiting for READY"}
        ready = _read_reply(parent_conn, deadline, max_bytes)
        if ready == "READY":
            return process, parent_conn
        reason = _isolation_error(ready)
        if reason is None:
            return {"code": 500, "error": "Isolated worker sent a malformed ready reply"}
        _one_shot_isolation_failures.log(reason)
        return ready
    except EOFError:
        # The traceback holds only this module's frames: the worker sent nothing.
        logger.warning("Isolated worker exited before sending READY", exc_info=True)
        return {"code": 500, "error": "Isolated worker exited before returning a result"}
    except _StalledReply:
        logger.warning("Isolated worker stalled while sending READY")
        return {"code": 500, "error": "Isolated worker timed out waiting for READY"}
    finally:
        child_conn.close()
        if ready != "READY":
            _stop_isolated_worker(process, parent_conn)


@functools.lru_cache(maxsize=1)
def _one_shot_settings() -> Union[Tuple[float, int], dict]:
    """The worker settings, read once per Studio job; an invalid one is logged once and fails every code metric of it."""
    try:
        return ready_timeout_seconds(), max_reply_bytes()
    except ValueError as e:
        logger.error(f"Code metrics can't run: {e}")
        return {"code": 500, "error": f"Invalid configuration: {e}"}


def run_user_code_in_isolated_worker(code: str, data: dict, payload_type: Optional[str] = None) -> dict:
    """Run user code in an isolated worker.

    For callers without a worker pool: Optimization Studio runs code metrics inside its
    job subprocess, which needs the network itself and so cannot be isolated as a whole.
    Workers are reused within that process, like pool workers, and replaced after any
    failure. No timeout here, as before: the job supervisor bounds the run, and isolated
    workers are killed when the job process exits.
    """
    settings = _one_shot_settings()
    if isinstance(settings, dict):
        return settings
    ready_timeout, max_bytes = settings
    worker = None
    # At most every worker idle now; a fresh one is used as it starts, so this never retries a start.
    for _ in range(_idle_isolated_workers.qsize()):
        try:
            worker = _idle_isolated_workers.get_nowait()
        except Empty:
            break
        if worker[0].is_alive() and _drain(worker[1], max_bytes):
            break
        _stop_isolated_worker(*worker)
        worker = None
    if worker is None:
        started = _isolated_worker_spawner.submit(_start_isolated_worker, ready_timeout, max_bytes).result()
        if isinstance(started, dict):
            return started
        worker = started
    process, connection = worker
    reusable = False
    try:
        request_id, command = _command(code, data, payload_type)
        connection.send(command)
        # No deadline for the run itself (see above), only a bound on pauses once the reply has started.
        connection.poll(None)
        result = _read_result(connection, request_id, math.inf, max_bytes, idle=_REPLY_GRACE_SECONDS)
        if result is None:
            return {"code": 400, "error": MALFORMED_REPLY_ERROR}
        reusable = True
        return result
    except _StalledReply:
        logger.warning("Isolated worker stalled while sending a result")
        return {"code": 504, "error": EXEC_TIMEOUT_ERROR}
    except (EOFError, BrokenPipeError) as e:
        logger.warning(f"Isolated worker exited before returning a result: {type(e).__name__}")
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
        # Here rather than at import, so only the process strategy depends on them; a bad value still fails startup.
        self.ready_timeout = ready_timeout_seconds()
        self.max_reply_bytes = max_reply_bytes()
        self._isolation_failures = _IsolationFailureLog()

        self.scheduler_thread = None

    def start_services(self):
        logger.info("ProcessExecutor: Registering signal handlers")
        signal.signal(signal.SIGINT, self._handle_shutdown_signal)
        signal.signal(signal.SIGTERM, self._handle_shutdown_signal)

        # Before the first worker forks, so isolated workers start from the template, not from this backend.
        if self.isolate:
            try:
                _start_template()
            except _IsolationUnavailable as e:
                # each worker retries the template and is refused, so no worker starts from this backend
                self._isolation_failures.log(str(e), e.__cause__)

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
        start_monotonic = time.monotonic()
        worker_id = str(uuid.uuid4())[:8]
        parent_conn = child_conn = process = None
        try:
            if self.isolate:
                # Idempotent; ensures the template was started with the reduced environment, not lazily by the fork.
                _start_template()
            ctx = _worker_context(self.isolate)
            parent_conn, child_conn = ctx.Pipe()
            process = ctx.Process(target=process_worker.worker_process_main, args=(child_conn, self.isolate))
            _start(process)

            # Wait for READY signal from worker
            if parent_conn.poll(timeout=self.ready_timeout):
                ready_signal = _read_reply(parent_conn, start_monotonic + self.ready_timeout, self.max_reply_bytes)
                reason = _isolation_error(ready_signal)
                if reason is not None:
                    raise _IsolationUnavailable(reason)
                if ready_signal != "READY":
                    # Only the type: the reply is the worker's data.
                    raise Exception(f"Worker {worker_id} (PID: {process.pid}) sent an unexpected ready reply "
                                    f"({type(ready_signal).__name__})")
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
                self._isolation_failures.log(str(e), e.__cause__)
            else:
                logger.error(
                    f"Failed to create worker process {worker_id} (PID: {process.pid if process and process.pid else 'N/A'}): {e}")
            if parent_conn:
                _close(parent_conn, worker_id)
            if child_conn:
                _close(child_conn, worker_id)
            if process:
                _stop_process(process, worker_id)

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
        if not alive or not _drain(worker['connection'], self.max_reply_bytes):
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
            deadline = time.monotonic() + self.exec_timeout
            request_id, command = _command(code, data, payload_type)
            connection.send(command)

            # Wait for result with timeout
            if connection.poll(timeout=self.exec_timeout):
                result = _read_result(connection, request_id, deadline, self.max_reply_bytes)
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
        except _StalledReply:
            # Mid-message, so the pipe can't be read again: treated as the exec timeout.
            logger.error(f"Worker {worker_id} stalled while sending a result")
            self._async_terminate(worker)
            return {"code": 504, "error": EXEC_TIMEOUT_ERROR}
        except Exception as e:
            logger.error(f"Error in run_scoring with worker {worker.get('id', 'unknown')}: {e}")
            # Dispatch termination off the request thread so the
            # SIGTERM/SIGKILL handshake doesn't block the caller.
            self._async_terminate(worker)
            return {"code": 500, "error": f"Failed to execute code: {e}"}
