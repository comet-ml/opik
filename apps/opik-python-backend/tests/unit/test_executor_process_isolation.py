"""Process workers isolate user code only with PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION=true.

Isolated, each worker drops to an unprivileged account, loses the inherited environment and
runs under a seccomp filter refusing new sockets and process inspection. That needs Linux
and root, so those tests skip elsewhere, where the fail-closed tests run instead.
"""
import logging
import os
import socket
import sys

import pytest

from opik_backend import executor_process, process_worker
from opik_backend.executor_process import (
    PROCESS_NETWORK_ISOLATION_ENV_VAR,
    ProcessExecutor,
    run_user_code_in_isolated_worker,
    terminate_worker,
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

    def score(self, port, full=False, **ignored_kwargs):
        reason = f"uid={os.getuid()} secret={os.environ.get('PROBE_SECRET')} tcp={attempt(lambda: tcp(port))}"
        if full:
            reason += (
                # libcs report a refused resolver socket differently; only the outcome matters.
                f" dns={'ok' if attempt(lambda: socket.getaddrinfo('example.com', 80)) == 'ok' else 'failed'}"
                f" unix={attempt(lambda: socket.socket(socket.AF_UNIX).close())}"
                f" ptrace={attempt(ptrace_parent)}"
            )
        return score_result.ScoreResult(name=self.name, value=1.0, reason=reason)
"""


def _scores(reason: str) -> dict:
    return {"scores": [{
        "name": "probe", "value": 1.0, "reason": reason, "category_name": None,
        "metadata": None, "scoring_failed": False,
    }]}


NOT_ISOLATED = _scores(f"uid={os.getuid()} secret=s3cr3t tcp=ok")
ISOLATED = _scores(f"uid={UNPRIVILEGED_ID} secret=None tcp=EPERM dns=failed unix=EPERM ptrace=EPERM")


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


@pytest.fixture(autouse=True)
def probe_env(monkeypatch):
    monkeypatch.setenv("PROBE_SECRET", "s3cr3t")
    # The failure is logged once per process; each test starts from a clean slate.
    monkeypatch.setattr(executor_process, "_isolation_failure_logged", False)


def _score_in_pool(data: dict) -> dict:
    executor = ProcessExecutor()
    executor.create_worker_process()
    worker = executor.process_pool.queue[0]
    try:
        return executor.run_scoring(PROBE_METRIC, data)
    finally:
        terminate_worker(worker)


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


@needs_isolation
@pytest.mark.parametrize("isolation", ["true", "True"])
def test_pool_worker_is_isolated_with_setting(monkeypatch, listener, isolation):
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)

    result = _score_in_pool({"port": listener, "full": True})

    assert result == ISOLATED


@needs_isolation
def test_isolated_one_shot_worker_scores_without_network(listener):
    result = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": listener, "full": True})

    assert result == ISOLATED


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
    with caplog.at_level(logging.INFO):
        result = run_user_code_in_isolated_worker(PROBE_METRIC, {"port": listener})

    assert result["code"] == 500
    assert result["error"].startswith("Worker isolation failed: ")
    _assert_single_isolation_error(caplog, "check platform" if sys.platform != "linux" else "switch to uid 65534")
