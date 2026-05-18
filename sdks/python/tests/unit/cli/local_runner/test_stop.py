"""Tests for opik.cli.local_runner.stop and the `opik <type> stop` CLI surface."""

import json
import os
import signal
from pathlib import Path
from typing import Optional
from unittest.mock import patch

import pytest
from click.testing import CliRunner

from opik.cli.local_runner import stop as stop_module
from opik.cli.local_runner.pairing import RunnerType
from opik.cli.local_runner.stop import do_stop
from opik.cli.main import cli
from opik.runner import pid_file
from opik.runner.pid_file import RunnerInfo


@pytest.fixture
def tmp_runners_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    runners = tmp_path / "runners"
    monkeypatch.setattr(pid_file, "_RUNNERS_DIR", runners)
    return runners


def _write_runner_file(
    dir: Path,
    runner_id: str,
    runner_type: str,
    project_name: str,
    pid: int,
    workspace: Optional[str] = None,
) -> Path:
    dir.mkdir(parents=True, exist_ok=True)
    path = dir / f"{runner_type}-{runner_id}.json"
    path.write_text(
        json.dumps(
            {
                "pid": pid,
                "runner_id": runner_id,
                "runner_type": runner_type,
                "project_name": project_name,
                "workspace": workspace,
                "started_at": 1.0,
            }
        )
    )
    return path


class TestDoStopFilters:
    def test_do_stop__no_filter__usage_error(self, tmp_runners_dir: Path) -> None:
        # No --project / --runner / --all → usage error before any signaling.
        with pytest.raises(Exception) as exc_info:
            do_stop(
                runner_type=RunnerType.CONNECT,
                project_name=None,
                all_flag=False,
                runner_id_filter=None,
            )
        # click.UsageError prints "Error: ..." and is a Click exception.
        assert "specify" in str(exc_info.value).lower()

    def test_do_stop__no_match__prints_no_match_message(
        self, tmp_runners_dir: Path, capsys: pytest.CaptureFixture
    ) -> None:
        do_stop(
            runner_type=RunnerType.CONNECT,
            project_name="missing",
            all_flag=False,
            runner_id_filter=None,
        )
        out = capsys.readouterr().out
        assert "No local 'connect' runners found" in out

    def test_do_stop__project_filter__signals_matching_only(
        self, tmp_runners_dir: Path
    ) -> None:
        _write_runner_file(tmp_runners_dir, "r-1", "connect", "alpha", os.getpid())
        _write_runner_file(tmp_runners_dir, "r-2", "connect", "beta", os.getpid())

        seen = []

        def fake_signal(info: RunnerInfo):
            seen.append(info.runner_id)
            return True, ""

        with patch.object(stop_module, "_signal_until_gone", side_effect=fake_signal):
            do_stop(
                runner_type=RunnerType.CONNECT,
                project_name="alpha",
                all_flag=False,
                runner_id_filter=None,
            )

        assert seen == ["r-1"]
        # The matched runner's pid file is cleaned up.
        assert not (tmp_runners_dir / "connect-r-1.json").exists()
        # Non-matched is left alone.
        assert (tmp_runners_dir / "connect-r-2.json").exists()

    def test_do_stop__all_flag__signals_only_target_runner_type(
        self, tmp_runners_dir: Path
    ) -> None:
        _write_runner_file(tmp_runners_dir, "r-1", "connect", "alpha", os.getpid())
        _write_runner_file(tmp_runners_dir, "r-2", "connect", "beta", os.getpid())
        # An endpoint runner must NOT be touched by `connect stop --all`.
        _write_runner_file(tmp_runners_dir, "r-3", "endpoint", "alpha", os.getpid())

        seen = []
        with patch.object(
            stop_module,
            "_signal_until_gone",
            lambda info: (seen.append(info.runner_id) or (True, "")),
        ):
            do_stop(
                runner_type=RunnerType.CONNECT,
                project_name=None,
                all_flag=True,
                runner_id_filter=None,
            )
        assert sorted(seen) == ["r-1", "r-2"]
        assert (tmp_runners_dir / "endpoint-r-3.json").exists()

    def test_do_stop__runner_id_filter__signals_matching_only(
        self, tmp_runners_dir: Path
    ) -> None:
        _write_runner_file(tmp_runners_dir, "r-1", "connect", "alpha", os.getpid())
        _write_runner_file(tmp_runners_dir, "r-2", "connect", "alpha", os.getpid())

        seen = []
        with patch.object(
            stop_module,
            "_signal_until_gone",
            lambda info: (seen.append(info.runner_id) or (True, "")),
        ):
            do_stop(
                runner_type=RunnerType.CONNECT,
                project_name=None,
                all_flag=False,
                runner_id_filter="r-2",
            )
        assert seen == ["r-2"]


class TestSignalUntilGone:
    """Logic tests for the SIGTERM → wait → SIGKILL flow.

    Real-subprocess kill is exercised by `tests/unit/runner/test_pid_file.py::TestSignalRoundtrip`;
    those cases call `proc.wait()` to reap zombies, which pytest's parent-child
    relationship requires. Here we mock the alive-check + signal so we can assert
    the escalation order without fighting zombie state.
    """

    def _make_info(self, tmp_runners_dir: Path, pid: int = 12345) -> RunnerInfo:
        return RunnerInfo(
            pid=pid,
            runner_id="r-1",
            runner_type="connect",
            project_name="p",
            workspace=None,
            started_at=0.0,
            path=tmp_runners_dir / "connect-r-1.json",
        )

    def test_signal_until_gone__target_exits_after_sigterm__returns_ok(
        self, tmp_runners_dir: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.setattr(stop_module, "_POLL_INTERVAL_SECONDS", 0.01)
        sent_signals = []
        monkeypatch.setattr(
            stop_module.os, "kill", lambda pid, sig: sent_signals.append((pid, sig))
        )
        # First poll says alive, next says gone.
        alive_seq = iter([True, False])
        monkeypatch.setattr(
            stop_module.pid_file, "is_pid_alive", lambda _pid: next(alive_seq)
        )

        ok, _ = stop_module._signal_until_gone(self._make_info(tmp_runners_dir))
        assert ok
        assert sent_signals == [(12345, signal.SIGTERM)]

    def test_signal_until_gone__sigterm_ignored__escalates_to_sigkill(
        self, tmp_runners_dir: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.setattr(stop_module, "_SIGTERM_GRACE_SECONDS", 0.05)
        monkeypatch.setattr(stop_module, "_POLL_INTERVAL_SECONDS", 0.01)
        sent_signals = []
        state = {"alive": True}

        def fake_kill(pid: int, sig: int) -> None:
            sent_signals.append((pid, sig))
            if sig == signal.SIGKILL:
                state["alive"] = False

        monkeypatch.setattr(stop_module.os, "kill", fake_kill)
        monkeypatch.setattr(
            stop_module.pid_file, "is_pid_alive", lambda _pid: state["alive"]
        )

        ok, _ = stop_module._signal_until_gone(self._make_info(tmp_runners_dir))
        assert ok
        assert [sig for _, sig in sent_signals] == [signal.SIGTERM, signal.SIGKILL]

    def test_signal_until_gone__already_dead_pid__returns_ok(
        self, tmp_runners_dir: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        def fake_kill(_pid: int, _sig: int) -> None:
            raise ProcessLookupError()

        monkeypatch.setattr(stop_module.os, "kill", fake_kill)
        ok, reason = stop_module._signal_until_gone(self._make_info(tmp_runners_dir))
        assert ok
        assert "exited" in reason

    def test_signal_until_gone__permission_error__returns_failure(
        self, tmp_runners_dir: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        def fake_kill(_pid: int, _sig: int) -> None:
            raise PermissionError("not yours")

        monkeypatch.setattr(stop_module.os, "kill", fake_kill)
        ok, reason = stop_module._signal_until_gone(self._make_info(tmp_runners_dir))
        assert not ok
        assert "permission" in reason.lower()


class TestStopExitCode:
    def test_do_stop__signal_failed__raises_systemexit_1(
        self, tmp_runners_dir: Path
    ) -> None:
        _write_runner_file(tmp_runners_dir, "r-1", "connect", "alpha", os.getpid())
        with patch.object(
            stop_module, "_signal_until_gone", return_value=(False, "denied")
        ):
            with pytest.raises(SystemExit) as exc_info:
                do_stop(
                    runner_type=RunnerType.CONNECT,
                    project_name="alpha",
                    all_flag=False,
                    runner_id_filter=None,
                )
            assert exc_info.value.code == 1


class TestConnectStopCli:
    def test_cli_connect_stop__no_filter__exits_with_usage_error(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["connect", "stop"])
        assert result.exit_code == 2
        assert "Specify" in result.output or "specify" in result.output

    def test_cli_connect_stop__with_project__routes_to_do_stop(
        self, tmp_runners_dir: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.local_runner.stop.do_stop") as mock_stop:
            result = runner.invoke(cli, ["connect", "stop", "--project", "p"])
            assert result.exit_code == 0, result.output
            mock_stop.assert_called_once()
            assert mock_stop.call_args.kwargs == dict(
                runner_type=RunnerType.CONNECT,
                project_name="p",
                all_flag=False,
                runner_id_filter=None,
            )

    def test_cli_connect_stop__all_flag__sets_all_flag_kwarg(
        self, tmp_runners_dir: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.local_runner.stop.do_stop") as mock_stop:
            result = runner.invoke(cli, ["connect", "stop", "--all"])
            assert result.exit_code == 0, result.output
            assert mock_stop.call_args.kwargs["all_flag"] is True

    def test_cli_connect_stop__runner_flag__sets_runner_id_filter(
        self, tmp_runners_dir: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.local_runner.stop.do_stop") as mock_stop:
            result = runner.invoke(cli, ["connect", "stop", "--runner", "r-1"])
            assert result.exit_code == 0, result.output
            assert mock_stop.call_args.kwargs["runner_id_filter"] == "r-1"


class TestEndpointStopCli:
    def test_cli_endpoint_stop__with_project__routes_to_do_stop(
        self, tmp_runners_dir: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.local_runner.stop.do_stop") as mock_stop:
            result = runner.invoke(cli, ["endpoint", "stop", "--project", "p"])
            assert result.exit_code == 0, result.output
            mock_stop.assert_called_once()
            assert mock_stop.call_args.kwargs == dict(
                runner_type=RunnerType.ENDPOINT,
                project_name="p",
                all_flag=False,
                runner_id_filter=None,
            )

    def test_cli_endpoint_stop__all_flag__sets_all_flag_kwarg(
        self, tmp_runners_dir: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.local_runner.stop.do_stop") as mock_stop:
            result = runner.invoke(cli, ["endpoint", "stop", "--all"])
            assert result.exit_code == 0, result.output
            assert mock_stop.call_args.kwargs["all_flag"] is True
