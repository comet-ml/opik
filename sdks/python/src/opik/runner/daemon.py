"""Unix double-fork daemon management for the runner process."""

import logging
import os
import signal
import sys
from pathlib import Path
from typing import Callable, Optional

LOGGER = logging.getLogger(__name__)

PID_FILE = Path.home() / ".opik" / "runner.pid"


def start_daemon(target: Callable[[], None]) -> int:
    """Start target as a background daemon using double-fork.

    Returns the daemon PID.
    """
    pid = os.fork()
    if pid > 0:
        # Parent waits briefly then returns daemon pid
        return pid

    # First child: create new session
    os.setsid()

    pid = os.fork()
    if pid > 0:
        # First child exits; second child is the daemon
        os._exit(0)

    # Daemon process
    _write_pid_file(os.getpid())

    # Redirect stdio to /dev/null
    devnull = os.open(os.devnull, os.O_RDWR)
    os.dup2(devnull, 0)
    os.dup2(devnull, 1)
    os.dup2(devnull, 2)
    os.close(devnull)

    try:
        target()
    except Exception:
        LOGGER.exception("Daemon crashed")
    finally:
        _remove_pid_file()
        os._exit(0)


def stop_daemon() -> bool:
    """Stop the running daemon. Returns True if stopped, False if not running."""
    pid = get_daemon_pid()
    if pid is None:
        return False

    try:
        os.kill(pid, signal.SIGTERM)
        _remove_pid_file()
        return True
    except ProcessLookupError:
        _remove_pid_file()
        return False


def get_daemon_pid() -> Optional[int]:
    """Get the PID of the running daemon, or None."""
    if not PID_FILE.exists():
        return None

    try:
        pid = int(PID_FILE.read_text().strip())
        # Check if process exists
        os.kill(pid, 0)
        return pid
    except (ValueError, ProcessLookupError, PermissionError):
        _remove_pid_file()
        return None


def _write_pid_file(pid: int) -> None:
    PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    PID_FILE.write_text(str(pid))


def _remove_pid_file() -> None:
    try:
        PID_FILE.unlink(missing_ok=True)
    except OSError:
        pass
