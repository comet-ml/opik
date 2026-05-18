"""PID/lock files for local runners.

Each `opik connect` or `opik endpoint` supervisor writes a small JSON file
under ``~/.opik/runners/`` carrying its PID plus enough metadata so that
``opik <type> stop`` can find and signal it from another terminal.

Stale entries (whose PID is no longer alive) are skipped on read; they are
purged opportunistically before any new write, so a crashed runner does not
leave permanent litter.
"""

import json
import logging
import os
import platform
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

LOGGER = logging.getLogger(__name__)

_RUNNERS_DIR = Path.home() / ".opik" / "runners"


@dataclass(frozen=True)
class RunnerInfo:
    pid: int
    runner_id: str
    runner_type: str
    project_name: str
    workspace: Optional[str]
    started_at: float
    path: Path


def runners_dir() -> Path:
    return _RUNNERS_DIR


def _file_for(runner_type: str, runner_id: str) -> Path:
    return _RUNNERS_DIR / f"{runner_type}-{runner_id}.json"


def write(
    *,
    runner_id: str,
    runner_type: str,
    project_name: str,
    workspace: Optional[str],
) -> Optional[Path]:
    """Create or overwrite this process's runner pid file.

    Returns the path on success, or None if the file could not be written —
    a missing pid file degrades `opik <type> stop` (the runner won't be
    discoverable) but must not prevent the supervisor from starting.
    """
    try:
        _RUNNERS_DIR.mkdir(parents=True, exist_ok=True)
    except OSError:
        LOGGER.warning("Could not create runners dir %s", _RUNNERS_DIR, exc_info=True)
        return None

    _purge_stale()

    path = _file_for(runner_type, runner_id)
    payload = {
        "pid": os.getpid(),
        "runner_id": runner_id,
        "runner_type": runner_type,
        "project_name": project_name,
        "workspace": workspace,
        "started_at": time.time(),
    }
    tmp = path.with_suffix(path.suffix + ".tmp")
    try:
        tmp.write_text(json.dumps(payload))
        tmp.replace(path)
    except OSError:
        LOGGER.warning("Could not write runner pid file %s", path, exc_info=True)
        try:
            tmp.unlink()
        except OSError:
            pass
        return None
    return path


def remove(*, runner_type: str, runner_id: str) -> None:
    path = _file_for(runner_type, runner_id)
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        LOGGER.error("Failed to remove pid file %s", path, exc_info=True)


def is_pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if platform.system() == "Windows":
        # We don't ship the headless runner on Windows yet. Conservative: assume gone
        # so stale entries get cleaned up rather than blocking deletion.
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # The pid exists but belongs to another user. Treat as alive; the caller
        # will get PermissionError again on the real signal and surface it.
        return True


def list_all(runner_type: Optional[str] = None) -> List[RunnerInfo]:
    """Return live runners, optionally filtered by type. Stale entries are skipped."""
    if not _RUNNERS_DIR.exists():
        return []
    results: List[RunnerInfo] = []
    for path in sorted(_RUNNERS_DIR.iterdir()):
        if path.suffix != ".json":
            continue
        info = _read_one(path)
        if info is None:
            continue
        if runner_type is not None and info.runner_type != runner_type:
            continue
        if not is_pid_alive(info.pid):
            continue
        results.append(info)
    return results


def _read_one(path: Path) -> Optional[RunnerInfo]:
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        LOGGER.debug("Skipping invalid pid file %s", path, exc_info=True)
        return None
    try:
        return RunnerInfo(
            pid=int(data["pid"]),
            runner_id=str(data["runner_id"]),
            runner_type=str(data["runner_type"]),
            project_name=str(data["project_name"]),
            workspace=data.get("workspace"),
            started_at=float(data.get("started_at", 0.0)),
            path=path,
        )
    except (KeyError, ValueError, TypeError):
        LOGGER.debug("Skipping malformed pid file %s", path, exc_info=True)
        return None


def _purge_stale() -> None:
    """Delete pid files whose PID is gone or whose payload is unreadable."""
    if not _RUNNERS_DIR.exists():
        return
    for path in _RUNNERS_DIR.iterdir():
        if path.suffix != ".json":
            continue
        info = _read_one(path)
        if info is None or not is_pid_alive(info.pid):
            try:
                path.unlink()
            except OSError:
                pass
