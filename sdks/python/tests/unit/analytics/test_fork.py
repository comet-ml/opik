"""
Threads do not survive `fork()`, so prefork servers and `multiprocessing` pools are
the one place reporting can silently stop - or, if handled carelessly, start counting
the same event twice.

The real fork happens in a subprocess: forking the pytest process itself while it has
threads running is a good way to produce flaky, hard-to-read failures.
"""

import subprocess
import sys
import textwrap

import pytest

from opik.analytics import api

FORK_SCRIPT = textwrap.dedent(
    """
    import os, sys
    os.environ["OPIK_ANALYTICS_ENABLE"] = "true"

    import httpx
    sent = []
    httpx.Client.post = lambda self, url, **kw: (
        sent.append(kw["json"]["event_type"]),
        type("R", (), {"status_code": 201})(),
    )[1]

    from opik import analytics
    from opik.analytics import rules

    # pytest is detected inside the subprocess too, so switch that rule off.
    rules._RULES = [rules._enabled_in_config]

    analytics.track_event("client", "create_dataset")
    analytics.flush(timeout=10)

    # Left in the queue, unsent, at the moment of the fork.
    analytics.track_event("client", "create_prompt")

    read_fd, write_fd = os.pipe()
    if os.fork() == 0:
        os.close(read_fd)
        sent.clear()
        analytics.track_event("client", "create_dataset")   # parent already sent this
        analytics.track_event("client", "search_traces")    # genuinely new
        analytics.flush(timeout=10)
        os.write(write_fd, repr(sent).encode())
        os.close(write_fd)
        os._exit(0)

    os.close(write_fd)
    child = eval(os.read(read_fd, 8192).decode())
    os.waitpid(-1, 0)
    analytics.flush(timeout=10)

    print(repr({"child": child, "parent": sent}))
    """
)


@pytest.fixture(scope="module")
def fork_result():
    completed = subprocess.run(
        [sys.executable, "-c", FORK_SCRIPT],
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert completed.returncode == 0, completed.stderr
    return eval(completed.stdout.strip().splitlines()[-1])


def _short(names):
    return [n.replace("opik_python_sdk__", "") for n in names]


@pytest.mark.skipif(not hasattr(__import__("os"), "fork"), reason="no fork on Windows")
def test_fork__child_reports_its_own_events(fork_result):
    """Without resetting the worker the child's events go nowhere at all."""
    assert "client__search_traces" in _short(fork_result["child"])


@pytest.mark.skipif(not hasattr(__import__("os"), "fork"), reason="no fork on Windows")
def test_fork__child_does_not_repeat_what_the_parent_reported(fork_result):
    """The child inherits `_ALREADY_REPORTED`, and must keep honouring it."""
    assert "client__create_dataset" not in _short(fork_result["child"])


@pytest.mark.skipif(not hasattr(__import__("os"), "fork"), reason="no fork on Windows")
def test_fork__events_queued_at_fork_time_are_sent_once(fork_result):
    """
    The child inherits the parent's queue. It gets a fresh one, so the events still
    pending at fork time are sent by the parent only.
    """
    parent = _short(fork_result["parent"])

    assert "client__create_prompt" in parent
    assert parent.count("client__create_prompt") == 1
    assert "client__create_prompt" not in _short(fork_result["child"])


def test_reset_after_fork__drops_the_worker_but_keeps_what_was_reported(monkeypatch):
    monkeypatch.setattr(api, "_WORKER", object())
    monkeypatch.setattr(api, "_ALREADY_REPORTED", {("opik_python_sdk__client__init",)})
    original_lock = api._LOCK

    api._reset_after_fork()

    assert api._WORKER is None
    assert api._ALREADY_REPORTED == {("opik_python_sdk__client__init",)}
    assert api._LOCK is not original_lock
