import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch


from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.local_runner_heartbeat_response import (
    LocalRunnerHeartbeatResponse,
)
from opik.runner.supervisor import Supervisor


_SENTINEL = object()


def _make_supervisor(
    command=_SENTINEL,
    env=None,
    repo_root=None,
    runner_id="runner-1",
    api=None,
) -> Supervisor:
    if command is _SENTINEL:
        command = [sys.executable, "-c", "import time; time.sleep(60)"]
    if env is None:
        env = dict(os.environ)
    if repo_root is None:
        repo_root = Path.cwd()
    if api is None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()
        api.runners.next_bridge_commands.return_value = MagicMock(commands=[])
    return Supervisor(
        command=command,
        env=env,
        repo_root=repo_root,
        runner_id=runner_id,
        api=api,
    )


class TestStartChild:
    def test_launches_process(self, tmp_path: Path) -> None:
        marker = tmp_path / "started"
        sup = _make_supervisor(
            command=[sys.executable, "-c", f"open('{marker}', 'w').write('ok')"],
            repo_root=tmp_path,
        )
        child = sup._start_child()
        child.wait(timeout=5)
        assert marker.read_text() == "ok"

    def test_captures_output_via_pipe(self) -> None:
        sup = _make_supervisor()
        with patch("opik.runner.supervisor.subprocess.Popen") as mock_popen:
            mock_proc = MagicMock()
            mock_proc.stdout = MagicMock()
            mock_proc.stdout.readline = MagicMock(return_value=b"")
            mock_proc.stderr = MagicMock()
            mock_proc.stderr.readline = MagicMock(return_value=b"")
            mock_popen.return_value = mock_proc
            sup._start_child()
            call_kwargs = mock_popen.call_args.kwargs
            assert call_kwargs.get("stdout") == subprocess.PIPE
            assert call_kwargs.get("stderr") == subprocess.PIPE


class TestStopChild:
    def test_sigterm_then_wait(self) -> None:
        sup = _make_supervisor()
        with sup._child_lock:
            sup._child = sup._start_child()
        assert sup._child.poll() is None
        sup._stop_child()
        assert sup._child is None

    def test_sigkill_after_timeout(self) -> None:
        sup = _make_supervisor(
            command=[
                sys.executable,
                "-c",
                "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)",
            ],
        )
        with sup._child_lock:
            sup._child = sup._start_child()
        time.sleep(0.2)
        sup._stop_child(graceful_timeout=1)
        assert sup._child is None

    def test_already_dead__no_error(self) -> None:
        sup = _make_supervisor(
            command=[sys.executable, "-c", "pass"],
        )
        with sup._child_lock:
            sup._child = sup._start_child()
        sup._child.wait(timeout=5)
        sup._stop_child()
        assert sup._child is None


class TestRestart:
    def test_stops_and_starts(self) -> None:
        sup = _make_supervisor()
        with sup._child_lock:
            sup._child = sup._start_child()
        old_pid = sup._child.pid

        sup._restart_child("test reason")

        assert sup._child is not None
        assert sup._child.pid != old_pid

        sup._stop_child()

    def test_debounce(self) -> None:
        sup = _make_supervisor()
        with sup._child_lock:
            sup._child = sup._start_child()

        start_count = 0
        original_start = sup._start_child

        def counting_start():
            nonlocal start_count
            start_count += 1
            return original_start()

        sup._start_child = counting_start

        # Three rapid calls — only the first should actually restart
        for i in range(3):
            sup._restart_child(f"trigger {i}")

        assert start_count == 1

        sup._stop_child()


class TestChildExit:
    def test_restarts_if_stable(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()

        sup = _make_supervisor(
            command=[sys.executable, "-c", "import sys; sys.exit(1)"],
            api=api,
        )

        restart_count = 0
        original_start = sup._start_child

        def counting_start():
            nonlocal restart_count
            restart_count += 1
            if restart_count >= 3:
                sup._shutdown_event.set()
            return original_start()

        sup._start_child = counting_start

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()
        t.join(timeout=10)

        assert restart_count >= 2

    def test_waits_if_unstable(self) -> None:
        sup = _make_supervisor(
            command=[sys.executable, "-c", "import sys; sys.exit(1)"],
        )
        sup._guard._max_crashes = 2
        sup._guard._window_seconds = 60.0

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(3)

        # Should be idle with no child, not shut down
        assert sup._child is None
        assert not sup._shutdown_event.is_set()

        sup._shutdown_event.set()
        t.join(timeout=10)

    def test_exit_0__no_restart(self) -> None:
        sup = _make_supervisor(
            command=[sys.executable, "-c", "pass"],
        )

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()
        t.join(timeout=10)

        assert sup._shutdown_event.is_set()


class TestShutdown:
    def test_stops_all(self) -> None:
        sup = _make_supervisor()

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(1)
        sup._shutdown_event.set()
        t.join(timeout=10)

        assert sup._child is None

    def test_waits_for_child(self) -> None:
        sup = _make_supervisor()

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(0.5)
        sup._shutdown_event.set()
        t.join(timeout=15)

        assert not t.is_alive()


class TestHeartbeat:
    def test_sends_capabilities(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()

        sup = _make_supervisor(api=api)

        t = threading.Thread(target=sup._heartbeat_loop, daemon=True)
        t.start()

        time.sleep(0.5)
        sup._shutdown_event.set()
        t.join(timeout=5)

        api.runners.heartbeat.assert_called()
        call_kwargs = api.runners.heartbeat.call_args.kwargs
        assert call_kwargs["capabilities"] == ["jobs", "bridge"]

    def test_410__shuts_down(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.side_effect = ApiError(status_code=410, body=None)

        sup = _make_supervisor(api=api)

        t = threading.Thread(target=sup._heartbeat_loop, daemon=True)
        t.start()
        t.join(timeout=5)

        assert sup._shutdown_event.is_set()


class TestStandaloneMode:
    def test_no_command__runs_without_child(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()

        sup = _make_supervisor(command=None, api=api)

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(1)

        assert sup._child is None

        sup._shutdown_event.set()
        t.join(timeout=10)

    def test_no_command__sends_checklist_with_null_command(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()

        sup = _make_supervisor(command=None, api=api)

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(1)
        sup._shutdown_event.set()
        t.join(timeout=10)

        api.runners.patch_checklist.assert_called()
        checklist = api.runners.patch_checklist.call_args.kwargs.get(
            "request"
        ) or api.runners.patch_checklist.call_args[1].get("request")
        assert checklist["command"] is None


class TestBridgeIntegration:
    def test_bridge_loop_runs(self) -> None:
        sup = _make_supervisor()

        t = threading.Thread(target=sup.run, daemon=True)
        t.start()

        time.sleep(1)

        bridge_alive = False
        for thread in threading.enumerate():
            if thread.name == "bridge-poll":
                bridge_alive = True
                break

        sup._shutdown_event.set()
        t.join(timeout=10)

        assert bridge_alive
