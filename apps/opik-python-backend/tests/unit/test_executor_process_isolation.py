"""Process workers isolate user code only with PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION=true.

Isolated, each worker drops to an unprivileged account, loses the inherited environment and
runs under a seccomp filter refusing new sockets and process inspection. That needs Linux
and root, so those tests skip elsewhere, where the fail-closed tests run instead.
"""
import builtins
import json
import logging
import os
import socket
import subprocess
import sys
import time
from typing import Iterator, List

import pytest

from opik_backend import create_app, executor_process, process_worker
from opik_backend.executor_process import (
    PROCESS_NETWORK_ISOLATION_ENV_VAR,
    ProcessExecutor,
    run_user_code_in_isolated_worker,
)
from opik_backend.process_worker import UNPRIVILEGED_ID

ALLOW_NETWORK_ENV_VAR = "PYTHON_CODE_EXECUTOR_ALLOW_NETWORK"


def _can_isolate() -> bool:
    if sys.platform != "linux":
        return False
    pid = os.fork()
    if pid == 0:
        try:
            process_worker.isolate_worker(keep_fd=-1)
            os._exit(0)
        except BaseException:
            os._exit(1)
    return os.waitpid(pid, 0)[1] == 0


CAN_ISOLATE = _can_isolate()
needs_isolation = pytest.mark.skipif(not CAN_ISOLATE, reason="needs Linux with root")
cannot_isolate = pytest.mark.skipif(CAN_ISOLATE, reason="isolation succeeds here")

PROBE_METRIC = """
import ctypes
import errno
import os
import socket

from opik.evaluation.metrics import base_metric, score_result


def attempt(action):
    try:
        action()
        return "ok"
    except OSError as e:
        return errno.errorcode[e.errno]


def tcp(port):
    socket.create_connection(("127.0.0.1", port), timeout=1).close()


def ptrace_parent():
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.ptrace(16, os.getppid(), 0, 0) != 0:  # PTRACE_ATTACH
        raise OSError(ctypes.get_errno(), "ptrace")


class Probe(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="probe", track=False)

    def score(self, port, full=False, file_fd=None, **ignored_kwargs):
        reason = f"uid={os.getuid()} secret={os.environ.get('PROBE_SECRET')} tcp={attempt(lambda: tcp(port))}"
        if full:
            reason += (
                # libcs report a refused resolver socket differently; only the outcome matters.
                f" dns={'ok' if attempt(lambda: socket.getaddrinfo('example.com', 80)) == 'ok' else 'failed'}"
                f" unix={attempt(lambda: socket.socket(socket.AF_UNIX).close())}"
                f" ptrace={attempt(ptrace_parent)}"
                f" file={os.pread(file_fd, 64, 0)!r}"
            )
        return score_result.ScoreResult(name=self.name, value=1.0, reason=reason)
"""


def _scores(reason: str) -> dict:
    return {"scores": [{
        "name": "probe", "value": 1.0, "reason": reason, "category_name": None,
        "metadata": None, "scoring_failed": False,
    }]}


NOT_ISOLATED = _scores(f"uid={os.getuid()} secret=s3cr3t tcp=ok")
ISOLATED = _scores(f"uid={UNPRIVILEGED_ID} secret=None tcp=EPERM dns=failed unix=EPERM ptrace=EPERM file=b''")

# Writes a reply into the worker's own pipe before the real one; unpickled, it would set a flag in the parent.
EARLY_REPLY_METRIC = """
import pickle
import sys

from opik.evaluation.metrics import base_metric, score_result


class SetsFlag:
    def __reduce__(self):
        return (exec, ("import builtins; builtins.EARLY_REPLY_UNPICKLED = True",))


class EarlyReply(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="early_reply", track=False)

    def score(self, **ignored_kwargs):
        frame = sys._getframe()
        while "connection" not in frame.f_locals:
            frame = frame.f_back
        frame.f_locals["connection"].send_bytes(pickle.dumps(SetsFlag()))
        return score_result.ScoreResult(name=self.name, value=1.0)
"""
MALFORMED = {"code": 400, "error": executor_process.MALFORMED_REPLY_ERROR}

# Values the evaluator endpoint encoded itself when worker replies were pickled.
ENDPOINT_ENCODED_METRIC = """
import dataclasses
import datetime
import decimal
import uuid

from opik.evaluation.metrics import base_metric, score_result


@dataclasses.dataclass
class Point:
    x: int
    y: int


class Encoded(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="encoded", track=False)

    def score(self, **ignored_kwargs):
        return score_result.ScoreResult(name=self.name, value=float("nan"), reason="r", metadata={
            "decimal": decimal.Decimal("0.1"),
            "datetime": datetime.datetime(2026, 1, 2, 3, 4, 5),
            "date": datetime.date(2026, 1, 2),
            "uuid": uuid.UUID("12345678-1234-5678-1234-567812345678"),
            "point": Point(1, 2),
            "tuple": (1, "a"),
            "int_keys": {1: "a", 2: "b"},
            "infinity": float("inf"),
        })
"""

# numpy values, which Studio read in-process as numbers and lists.
NUMPY_METRIC = """
import numpy

from opik.evaluation.metrics import base_metric, score_result


class Numpy(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="numpy", track=False)

    def score(self, **ignored_kwargs):
        return score_result.ScoreResult(name=self.name, value=numpy.float32(0.25), metadata={
            "int": numpy.int64(3), "bool": numpy.bool_(True), "array": numpy.array([[1, 2], [3, 4]]),
        })
"""

UNENCODABLE_METRIC = """
from opik.evaluation.metrics import base_metric, score_result


class Unencodable(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="unencodable", track=False)

    def score(self, **ignored_kwargs):
        return score_result.ScoreResult(name=self.name, value=1.0, metadata={"set": {1}})
"""

# Writes replies to the pipe after its own reply is sent, one echoing its own request id.
LATE_REPLY_METRIC = """
import json
import sys
import threading
import time

from opik.evaluation.metrics import base_metric, score_result


class LateReply(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="late_reply", track=False)

    def score(self, **ignored_kwargs):
        frame = sys._getframe()
        while "connection" not in frame.f_locals:
            frame = frame.f_back
        connection, request_id = frame.f_locals["connection"], frame.f_locals["request_id"]
        scores = {"scores": [{"name": "late", "value": 0.0}]}

        def write_later():
            time.sleep(0.2)
            for reply in ({"request_id": request_id, "result": scores}, {"result": scores}, scores):
                connection.send_bytes(json.dumps(reply).encode())

        threading.Thread(target=write_later).start()
        return score_result.ScoreResult(name=self.name, value=1.0)
"""

# Writes a reply carrying another request id ahead of its own.
OTHER_ID_REPLY_METRIC = """
import json
import sys

from opik.evaluation.metrics import base_metric, score_result


class OtherIdReply(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="other_id_reply", track=False)

    def score(self, **ignored_kwargs):
        frame = sys._getframe()
        while "connection" not in frame.f_locals:
            frame = frame.f_back
        reply = {"request_id": "0" * 32, "result": {"scores": [{"name": "other", "value": 0.0}]}}
        frame.f_locals["connection"].send_bytes(json.dumps(reply).encode())
        return score_result.ScoreResult(name=self.name, value=1.0)
"""

# Starts a process of its own, as a metric may.
CHILD_PROCESS_METRIC = """
import multiprocessing

from opik.evaluation.metrics import base_metric, score_result


class ChildProcess(base_metric.BaseMetric):
    def __init__(self):
        super().__init__(name="child_process", track=False)

    def score(self, **ignored_kwargs):
        child = multiprocessing.get_context("fork").Process(target=lambda: None)
        child.start()
        child.join()
        return score_result.ScoreResult(name=self.name, value=float(child.exitcode == 0))
"""

# A job that scores once in an isolated worker and exits, leaving the worker idle.
JOB_SCRIPT = f"""
from opik_backend.executor_process import run_user_code_in_isolated_worker
print(run_user_code_in_isolated_worker({CHILD_PROCESS_METRIC!r}, {{}})["scores"][0]["value"])
"""

ISOLATION_SETTINGS = ["false", pytest.param("true", marks=needs_isolation)]


def _only_score(name: str, value: float) -> dict:
    return {"scores": [{
        "name": name, "value": value, "reason": None, "category_name": None,
        "metadata": None, "scoring_failed": False,
    }]}


@pytest.fixture
def listener():
    """A port the test process itself can reach."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen()
    try:
        yield server.getsockname()[1]
    finally:
        server.close()


@pytest.fixture
def secret_file(tmp_path) -> Iterator[int]:
    """A descriptor open in the test process, which workers inherit."""
    path = tmp_path / "secret"
    path.write_text("s3cr3t")
    fd = os.open(path, os.O_RDONLY)
    try:
        yield fd
    finally:
        os.close(fd)


@pytest.fixture(autouse=True)
def probe_env(monkeypatch):
    monkeypatch.setenv("PROBE_SECRET", "s3cr3t")
    # One-shot failures are logged once per process; each test starts from a clean slate.
    monkeypatch.setattr(executor_process, "_one_shot_isolation_failures", executor_process._IsolationFailureLog())


@pytest.fixture(autouse=True)
def workers(monkeypatch) -> Iterator[List]:
    """Records every worker process a test starts and reaps it afterwards, whatever the test did."""
    created = []
    context = executor_process._mp_context
    real_process = context.Process

    def recording_process(*args, **kwargs):
        process = real_process(*args, **kwargs)
        created.append(process)
        return process

    monkeypatch.setattr(context, "Process", recording_process)
    yield created
    for process in created:
        if process.pid is not None:
            process.kill()
            process.join()


def _score_in_pool(data: dict, code: str = PROBE_METRIC) -> dict:
    executor = ProcessExecutor()
    executor.create_worker_process()
    return executor.run_scoring(code, data)


@pytest.mark.parametrize("isolation", [None, "false", "FALSE", ""])
@pytest.mark.parametrize("allow_network", [None, "false", "true"])
def test_pool_worker_is_not_isolated_without_setting(monkeypatch, listener, isolation, allow_network):
    for name, value in ((PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation), (ALLOW_NETWORK_ENV_VAR, allow_network)):
        if value is None:
            monkeypatch.delenv(name, raising=False)
        else:
            monkeypatch.setenv(name, value)

    result = _score_in_pool({"port": listener})

    assert result == NOT_ISOLATED


@pytest.mark.parametrize("isolation", ["yes", "1", "on", " true"])
def test_invalid_setting_fails_startup(monkeypatch, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)

    with pytest.raises(ValueError, match=f"^{PROCESS_NETWORK_ISOLATION_ENV_VAR} must be true or false, not "):
        ProcessExecutor()


@needs_isolation
@pytest.mark.parametrize("isolation", ["true", "True"])
def test_pool_worker_is_isolated_with_setting(monkeypatch, listener, secret_file, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)

    result = _score_in_pool({"port": listener, "full": True, "file_fd": secret_file})

    assert result == ISOLATED


@needs_isolation
def test_isolated_one_shot_worker_scores_without_network(listener, secret_file):
    result = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": listener, "full": True, "file_fd": secret_file})

    assert result == ISOLATED


@needs_isolation
def test_isolated_one_shot_worker_is_reused(listener, secret_file, workers):
    data = {"port": listener, "full": True, "file_fd": secret_file}

    results = [run_user_code_in_isolated_worker(PROBE_METRIC, data) for _ in range(3)]

    assert results == [ISOLATED] * 3
    assert len(workers) == 1


@pytest.mark.parametrize("isolation", ["false", pytest.param("true", marks=needs_isolation)])
def test_pool_worker_reply_is_read_as_data(monkeypatch, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)

    result = _score_in_pool({}, EARLY_REPLY_METRIC)

    assert result == MALFORMED
    assert not hasattr(builtins, "EARLY_REPLY_UNPICKLED")


def test_oversized_reply_is_an_error(monkeypatch, listener):
    monkeypatch.setattr(executor_process, "MAX_REPLY_BYTES", 16)

    result = _score_in_pool({"port": listener})

    assert result == MALFORMED


@needs_isolation
def test_one_shot_worker_reply_is_read_as_data():
    result = run_user_code_in_isolated_worker(EARLY_REPLY_METRIC, {})

    assert result == MALFORMED
    assert not hasattr(builtins, "EARLY_REPLY_UNPICKLED")


def _isolation_errors(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.levelno >= logging.ERROR]


def _assert_single_isolation_error(caplog, failed_step: str) -> None:
    errors = _isolation_errors(caplog)
    assert len(errors) == 1, errors
    assert errors[0].startswith(
        f"{PROCESS_NETWORK_ISOLATION_ENV_VAR}=true but process workers cannot be isolated ({failed_step} failed: "
    ), errors[0]


@needs_isolation
def test_worker_whose_filter_cannot_be_installed_never_joins_pool(monkeypatch, caplog):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, "true")

    def refuse() -> None:
        raise OSError("simulated")

    # Forked workers inherit the patched module.
    monkeypatch.setattr(process_worker, "_install_seccomp_filter", refuse)
    executor = ProcessExecutor()

    with caplog.at_level(logging.INFO):
        executor.create_worker_process()
        executor.create_worker_process()

    assert executor.process_pool.qsize() == 0
    _assert_single_isolation_error(caplog, "install seccomp filter")


@cannot_isolate
def test_worker_that_cannot_isolate_never_joins_pool(monkeypatch, caplog):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, "true")
    executor = ProcessExecutor()

    with caplog.at_level(logging.INFO):
        executor.create_worker_process()
        executor.create_worker_process()

    assert executor.process_pool.qsize() == 0
    _assert_single_isolation_error(caplog, "check platform" if sys.platform != "linux" else "switch to uid 65534")


@cannot_isolate
def test_one_shot_worker_that_cannot_isolate_returns_error(listener, caplog):
    reason = (
        f"check platform failed: needs Linux, not {sys.platform}"
        if sys.platform != "linux"
        else "switch to uid 65534 failed: [Errno 1] Operation not permitted"
    )

    with caplog.at_level(logging.INFO):
        result = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": listener})

    assert result == {
        "code": 500,
        "error": f"Worker isolation failed: {reason}",
        process_worker.ISOLATION_ERROR_KEY: reason,
    }
    _assert_single_isolation_error(caplog, "check platform" if sys.platform != "linux" else "switch to uid 65534")


@pytest.mark.parametrize("isolation", ISOLATION_SETTINGS)
def test_reply_is_encoded_as_the_endpoint_encoded_it(monkeypatch, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)
    app = create_app(should_init_executor=False)
    app.executor = ProcessExecutor()
    app.executor.create_worker_process()

    response = app.test_client().post(
        "/v1/private/evaluators/python", json={"code": ENDPOINT_ENCODED_METRIC, "data": {}}
    )

    # Flask's own encoding of these values, which the endpoint applied to the worker's objects.
    assert response.status_code == 200
    assert response.get_data() == (
        b'{"scores":[{"category_name":null,"metadata":{"date":"Fri, 02 Jan 2026 00:00:00 GMT",'
        b'"datetime":"Fri, 02 Jan 2026 03:04:05 GMT","decimal":"0.1","infinity":Infinity,'
        b'"int_keys":{"1":"a","2":"b"},"point":{"x":1,"y":2},"tuple":[1,"a"],'
        b'"uuid":"12345678-1234-5678-1234-567812345678"},"name":"encoded","reason":"r",'
        b'"scoring_failed":false,"value":NaN}]}\n'
    )


@pytest.mark.parametrize("isolation", ISOLATION_SETTINGS)
def test_numpy_values_are_plain_values(monkeypatch, isolation):
    pytest.importorskip("numpy")
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)

    result = _score_in_pool({}, NUMPY_METRIC)

    assert result == {"scores": [{
        "name": "numpy", "value": 0.25, "reason": None, "category_name": None,
        "metadata": {"int": 3, "bool": True, "array": [[1, 2], [3, 4]]}, "scoring_failed": False,
    }]}
    assert type(result["scores"][0]["value"]) is float


def test_unencodable_value_is_a_worker_error():
    result = _score_in_pool({}, UNENCODABLE_METRIC)

    assert result["code"] == 500
    assert result["error"] == "Worker error: Object of type set is not JSON serializable"


@pytest.mark.parametrize("isolation", ISOLATION_SETTINGS)
def test_replies_left_by_a_request_do_not_reach_the_next(monkeypatch, isolation, workers):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)
    executor = ProcessExecutor()
    executor.create_worker_process()

    first = executor.run_scoring(LATE_REPLY_METRIC, {})
    # The late replies are on the pipe before the next request is sent.
    assert executor.process_pool.queue[0]["connection"].poll(5)
    time.sleep(0.2)
    second = executor.run_scoring(PROBE_METRIC, {"port": 1})

    assert first == _only_score("late_reply", 1.0)
    assert [(score["name"], score["value"]) for score in second["scores"]] == [("probe", 1.0)]
    assert len(workers) == 1


@needs_isolation
def test_replies_left_by_a_request_do_not_reach_the_next_one_shot(workers):
    first = run_user_code_in_isolated_worker(LATE_REPLY_METRIC, {})
    assert executor_process._idle_isolated_workers.queue[0][1].poll(5)
    time.sleep(0.2)
    second = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": 1})

    assert first == _only_score("late_reply", 1.0)
    assert [(score["name"], score["value"]) for score in second["scores"]] == [("probe", 1.0)]
    assert len(workers) == 1


@pytest.mark.parametrize("isolation", ISOLATION_SETTINGS)
def test_reply_with_another_request_id_is_rejected(monkeypatch, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)
    executor = ProcessExecutor()
    executor.create_worker_process()

    result = executor.run_scoring(OTHER_ID_REPLY_METRIC, {})

    assert result == MALFORMED
    assert executor.process_pool.qsize() == 0


@pytest.mark.parametrize("reply", [
    {"request_id": "b" * 32, "result": {"scores": []}},
    {"result": {"scores": []}},
    {"request_id": "a" * 32, "result": "READY"},
    {"scores": []},
])
def test_reply_without_its_request_id_is_not_a_result(reply):
    parent, child = executor_process._mp_context.Pipe()
    child.send_bytes(json.dumps(reply).encode())

    assert executor_process._read_result(parent, "a" * 32) is None


def test_reply_with_its_request_id_is_the_result():
    parent, child = executor_process._mp_context.Pipe()
    child.send_bytes(json.dumps({"request_id": "a" * 32, "result": {"scores": []}}).encode())

    assert executor_process._read_result(parent, "a" * 32) == {"scores": []}


def test_every_command_has_its_own_request_id():
    ids = {executor_process._command("code", {}, None)[0] for _ in range(100)}

    assert len(ids) == 100


@needs_isolation
def test_isolated_worker_can_start_processes():
    result = run_user_code_in_isolated_worker(CHILD_PROCESS_METRIC, {})

    assert result == _only_score("child_process", 1.0)


@needs_isolation
def test_job_exit_ends_idle_isolated_workers():
    job = subprocess.run([sys.executable, "-c", JOB_SCRIPT], capture_output=True, text=True, timeout=60)

    assert (job.returncode, job.stdout) == (0, "1.0\n")


def _never_ready(connection, isolate: bool) -> None:
    time.sleep(60)


@pytest.mark.skipif(sys.platform != "linux", reason="forked workers inherit the patched target")
def test_isolated_worker_that_never_gets_ready_is_an_error(monkeypatch, workers):
    monkeypatch.setattr(executor_process, "READY_TIMEOUT_SECONDS", 0.5)
    monkeypatch.setattr(process_worker, "worker_process_main", _never_ready)

    result = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": 1})

    assert result == {"code": 500, "error": "Isolated worker timed out waiting for READY"}
    assert not workers[0].is_alive()


@pytest.mark.parametrize("value", ["0", "-1", "1.5", "64MiB"])
def test_invalid_max_reply_bytes_fails_startup(monkeypatch, value):
    monkeypatch.setenv(executor_process.MAX_REPLY_BYTES_ENV_VAR, value)

    with pytest.raises(ValueError, match=f"^{executor_process.MAX_REPLY_BYTES_ENV_VAR} must be a positive integer, not "):
        executor_process.max_reply_bytes()


@pytest.mark.parametrize("value, expected", [(None, 64 * 1024 * 1024), ("", 64 * 1024 * 1024), ("1024", 1024)])
def test_max_reply_bytes_defaults_when_unset(monkeypatch, value, expected):
    if value is None:
        monkeypatch.delenv(executor_process.MAX_REPLY_BYTES_ENV_VAR, raising=False)
    else:
        monkeypatch.setenv(executor_process.MAX_REPLY_BYTES_ENV_VAR, value)

    assert executor_process.max_reply_bytes() == expected
