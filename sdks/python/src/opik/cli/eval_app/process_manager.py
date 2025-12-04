"""Process management for the eval app server."""

import os
import signal
import time
from pathlib import Path
from typing import Optional, Tuple

# PID file location
_PID_FILE = Path.home() / ".opik" / "eval_app.pid"


def get_pid() -> Optional[int]:
    """Get the PID from the PID file if it exists."""
    if _PID_FILE.exists():
        try:
            return int(_PID_FILE.read_text().strip())
        except (ValueError, OSError):
            return None
    return None


def write_pid(pid: int) -> None:
    """Write the current PID to the PID file."""
    _PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    _PID_FILE.write_text(str(pid))


def remove_pid() -> None:
    """Remove the PID file."""
    if _PID_FILE.exists():
        _PID_FILE.unlink()


def is_process_running(pid: int) -> bool:
    """Check if a process with the given PID is running."""
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def stop_process(pid: int, timeout: float = 1.0) -> bool:
    """
    Stop a process by PID.

    Args:
        pid: Process ID to stop.
        timeout: Time to wait for process to terminate.

    Returns:
        True if process was stopped successfully, False otherwise.
    """
    if not is_process_running(pid):
        return True

    try:
        os.kill(pid, signal.SIGTERM)
        time.sleep(timeout)
        return not is_process_running(pid)
    except OSError:
        return False


def get_server_status() -> Tuple[bool, Optional[int]]:
    """
    Get the current server status.

    Returns:
        Tuple of (is_running, pid). If not running, pid may be None or stale.
    """
    pid = get_pid()
    if pid is None:
        return False, None

    if is_process_running(pid):
        return True, pid
    else:
        # Stale PID file
        return False, pid

