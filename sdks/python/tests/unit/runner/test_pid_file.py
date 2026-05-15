"""Tests for opik.runner.pid_file."""

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

from opik.runner import pid_file


@pytest.fixture
def tmp_runners_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    runners = tmp_path / "runners"
    monkeypatch.setattr(pid_file, "_RUNNERS_DIR", runners)
    return runners


class TestWrite:
    def test_creates_file_with_expected_payload(self, tmp_runners_dir: Path) -> None:
        path = pid_file.write(
            runner_id="r-1",
            runner_type="connect",
            project_name="my-proj",
            workspace="my-ws",
        )
        assert path is not None
        assert path.parent == tmp_runners_dir
        data = json.loads(path.read_text())
        assert data["pid"] == os.getpid()
        assert data["runner_id"] == "r-1"
        assert data["runner_type"] == "connect"
        assert data["project_name"] == "my-proj"
        assert data["workspace"] == "my-ws"
        assert isinstance(data["started_at"], float)

    def test_creates_runners_dir_if_missing(self, tmp_runners_dir: Path) -> None:
        assert not tmp_runners_dir.exists()
        pid_file.write(
            runner_id="r-1",
            runner_type="endpoint",
            project_name="p",
            workspace=None,
        )
        assert tmp_runners_dir.exists()

    def test_overwrites_existing_file(self, tmp_runners_dir: Path) -> None:
        pid_file.write(
            runner_id="r-1", runner_type="connect", project_name="a", workspace=None
        )
        path = pid_file.write(
            runner_id="r-1", runner_type="connect", project_name="b", workspace=None
        )
        assert path is not None
        data = json.loads(path.read_text())
        assert data["project_name"] == "b"

    def test_returns_none_on_oserror(
        self, tmp_runners_dir: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        def boom(*_a, **_kw):
            raise OSError("disk full")

        monkeypatch.setattr(Path, "mkdir", lambda *_a, **_kw: boom())
        result = pid_file.write(
            runner_id="r-1", runner_type="connect", project_name="p", workspace=None
        )
        assert result is None


class TestRemove:
    def test_deletes_existing_file(self, tmp_runners_dir: Path) -> None:
        pid_file.write(
            runner_id="r-1", runner_type="connect", project_name="p", workspace=None
        )
        pid_file.remove(runner_type="connect", runner_id="r-1")
        assert not (tmp_runners_dir / "connect-r-1.json").exists()

    def test_missing_file_is_noop(self, tmp_runners_dir: Path) -> None:
        # Must not raise on a runner that was never written or already cleaned up.
        pid_file.remove(runner_type="connect", runner_id="absent")


class TestIsPidAlive:
    def test_self_is_alive(self) -> None:
        assert pid_file.is_pid_alive(os.getpid())

    def test_zero_and_negative_are_dead(self) -> None:
        assert not pid_file.is_pid_alive(0)
        assert not pid_file.is_pid_alive(-1)

    def test_exited_subprocess_is_dead(self) -> None:
        proc = subprocess.Popen([sys.executable, "-c", "pass"])
        proc.wait(timeout=5)
        # Spin briefly while the kernel reaps.
        for _ in range(20):
            if not pid_file.is_pid_alive(proc.pid):
                break
            time.sleep(0.05)
        assert not pid_file.is_pid_alive(proc.pid)


class TestListAll:
    def _write_raw(self, dir: Path, name: str, payload: dict) -> Path:
        dir.mkdir(parents=True, exist_ok=True)
        path = dir / name
        path.write_text(json.dumps(payload))
        return path

    def test_empty_dir_returns_empty(self, tmp_runners_dir: Path) -> None:
        assert pid_file.list_all() == []

    def test_skips_stale_pids(self, tmp_runners_dir: Path) -> None:
        # PID 1 (init) is alive; a high-number PID is very unlikely to be alive.
        self._write_raw(
            tmp_runners_dir,
            "connect-alive.json",
            {
                "pid": os.getpid(),
                "runner_id": "alive",
                "runner_type": "connect",
                "project_name": "p",
                "workspace": None,
                "started_at": 1.0,
            },
        )
        self._write_raw(
            tmp_runners_dir,
            "connect-dead.json",
            {
                "pid": 2_147_483_640,
                "runner_id": "dead",
                "runner_type": "connect",
                "project_name": "p",
                "workspace": None,
                "started_at": 1.0,
            },
        )
        ids = [r.runner_id for r in pid_file.list_all()]
        assert ids == ["alive"]

    def test_filters_by_runner_type(self, tmp_runners_dir: Path) -> None:
        for runner_type in ("connect", "endpoint"):
            self._write_raw(
                tmp_runners_dir,
                f"{runner_type}-1.json",
                {
                    "pid": os.getpid(),
                    "runner_id": "1",
                    "runner_type": runner_type,
                    "project_name": "p",
                    "workspace": None,
                    "started_at": 1.0,
                },
            )
        connect_only = pid_file.list_all(runner_type="connect")
        assert [r.runner_type for r in connect_only] == ["connect"]

    def test_skips_malformed_files(self, tmp_runners_dir: Path) -> None:
        tmp_runners_dir.mkdir(parents=True)
        (tmp_runners_dir / "connect-bad.json").write_text("not json {")
        (tmp_runners_dir / "connect-missing-fields.json").write_text(
            json.dumps({"pid": 1})
        )
        assert pid_file.list_all() == []

    def test_ignores_non_json_files(self, tmp_runners_dir: Path) -> None:
        tmp_runners_dir.mkdir(parents=True)
        (tmp_runners_dir / "README.txt").write_text("hi")
        assert pid_file.list_all() == []


class TestPurgeStale:
    def test_purges_dead_entries_on_write(self, tmp_runners_dir: Path) -> None:
        # Seed a stale file, then write a new one and confirm the stale was removed.
        tmp_runners_dir.mkdir(parents=True)
        stale = tmp_runners_dir / "connect-stale.json"
        stale.write_text(
            json.dumps(
                {
                    "pid": 2_147_483_640,
                    "runner_id": "stale",
                    "runner_type": "connect",
                    "project_name": "p",
                    "workspace": None,
                    "started_at": 1.0,
                }
            )
        )
        pid_file.write(
            runner_id="r-new",
            runner_type="connect",
            project_name="p",
            workspace=None,
        )
        assert not stale.exists()

    def test_purges_malformed_on_write(self, tmp_runners_dir: Path) -> None:
        tmp_runners_dir.mkdir(parents=True)
        bad = tmp_runners_dir / "connect-bad.json"
        bad.write_text("not json")
        pid_file.write(
            runner_id="r-new",
            runner_type="connect",
            project_name="p",
            workspace=None,
        )
        assert not bad.exists()


class TestSignalRoundtrip:
    """Spin up a sleeping subprocess, write a pid file for it, signal it via the
    discovered pid, and check the file/process disappear. Guards the contract
    that `opik <type> stop` relies on: list_all → os.kill → process exits."""

    def test_sigterm_via_pid_file(self, tmp_runners_dir: Path) -> None:
        proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            tmp_runners_dir.mkdir(parents=True)
            payload = {
                "pid": proc.pid,
                "runner_id": "r-1",
                "runner_type": "connect",
                "project_name": "p",
                "workspace": None,
                "started_at": 1.0,
            }
            (tmp_runners_dir / "connect-r-1.json").write_text(json.dumps(payload))

            runners = pid_file.list_all(runner_type="connect")
            assert len(runners) == 1
            assert runners[0].pid == proc.pid

            os.kill(runners[0].pid, signal.SIGTERM)
            proc.wait(timeout=5)
            assert proc.returncode is not None
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=5)
