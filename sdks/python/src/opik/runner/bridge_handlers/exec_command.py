"""exec bridge command handler — runs shell commands in the project root."""

import functools
import logging
import os
import platform
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from . import BaseHandler, CommandError

LOGGER = logging.getLogger(__name__)

_MAX_OUTPUT_BYTES = 512 * 1024
_DEFAULT_TIMEOUT_SECONDS = 30
_MAX_TIMEOUT_SECONDS = 120
_DEFAULT_MAX_BACKGROUND = 5
_GRACEFUL_KILL_TIMEOUT_SECONDS = 5
_BG_STARTUP_WAIT_SECONDS = 3
_BG_LOG_DIR = Path(tempfile.gettempdir())


@functools.cache
def _has_stdbuf() -> bool:
    """Check if stdbuf is available (coreutils on Linux, homebrew on macOS)."""
    return shutil.which("stdbuf") is not None


_BLOCKLIST = [
    re.compile(r"\bsudo\b"),
    re.compile(r"\bsu\b"),
    re.compile(r"\bdoas\b"),
    re.compile(r"(?:^|[;&|]\s*)nohup\b"),
    re.compile(r"(?:^|[;&|]\s*)disown\b"),
    re.compile(
        r"\brm\b[^|;]*-[a-zA-Z]*r[a-zA-Z]*[^|;]*-[a-zA-Z]*f[a-zA-Z]*[^|;]*\s+[/~*]"
    ),
    re.compile(
        r"\brm\b[^|;]*-[a-zA-Z]*f[a-zA-Z]*[^|;]*-[a-zA-Z]*r[a-zA-Z]*[^|;]*\s+[/~*]"
    ),
    re.compile(r"\brm\s+-[a-zA-Z]*rf[a-zA-Z]*\s+[/~*]"),
    re.compile(r":\(\)\s*\{.*:\|:.*\}"),
    re.compile(r"\bdd\s+if="),
    re.compile(r"\bmkfs\b"),
    re.compile(r"\bshred\b"),
    re.compile(r"\bcurl\b.*\|\s*\b(bash|sh|zsh|fish|python[23]?)\b"),
    re.compile(r"\bwget\b.*\|\s*\b(bash|sh|zsh|fish|python[23]?)\b"),
    re.compile(r"\bchmod\s+777\s+/"),
    re.compile(r">\s*/dev/(sd[a-z]|nvme\d|vd[a-z]|xvd[a-z]|hd[a-z])"),
]


class ExecArgs(BaseModel):
    command: str
    timeout: Optional[int] = Field(default=None, ge=1, le=_MAX_TIMEOUT_SECONDS)
    background: bool = False


class BackgroundProcessTracker:
    def __init__(self, max_processes: int = _DEFAULT_MAX_BACKGROUND) -> None:
        self._max = max_processes
        self._procs: Dict[int, subprocess.Popen] = {}
        self._lock = threading.Lock()

    def register(self, proc: subprocess.Popen) -> None:
        with self._lock:
            self._procs = {pid: p for pid, p in self._procs.items() if p.poll() is None}
            if len(self._procs) >= self._max:
                raise CommandError(
                    "limit_reached",
                    f"Maximum background processes ({self._max}) reached",
                )
            self._procs[proc.pid] = proc

    def shutdown(self) -> None:
        with self._lock:
            procs = list(self._procs.values())
            self._procs.clear()

        if not procs:
            return

        alive: List[subprocess.Popen] = []
        for proc in procs:
            if proc.poll() is None:
                try:
                    if platform.system() == "Windows":
                        proc.terminate()
                    else:
                        os.killpg(proc.pid, signal.SIGTERM)
                except OSError:
                    pass
                alive.append(proc)

        if not alive:
            return

        deadline = time.monotonic() + _GRACEFUL_KILL_TIMEOUT_SECONDS
        still_alive: List[subprocess.Popen] = []
        for proc in alive:
            remaining = max(0, deadline - time.monotonic())
            try:
                proc.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                still_alive.append(proc)

        for proc in still_alive:
            try:
                if platform.system() == "Windows":
                    proc.kill()
                else:
                    os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                pass
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass


class ExecHandler(BaseHandler):
    def __init__(
        self,
        repo_root: Path,
        bg_tracker: Optional[BackgroundProcessTracker] = None,
        bg_startup_wait: float = _BG_STARTUP_WAIT_SECONDS,
    ) -> None:
        self._repo_root = repo_root
        self._bg_tracker = bg_tracker
        self._bg_startup_wait = bg_startup_wait

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = ExecArgs(**args)

        if not parsed.command.strip():
            raise CommandError("invalid_command", "Empty command")

        for pattern in _BLOCKLIST:
            if pattern.search(parsed.command):
                raise CommandError("blocked", "Command blocked by safety filter")

        if parsed.background:
            shell_args = self._shell_args(parsed.command, line_buffered=True)
            return self._execute_background(shell_args)

        shell_args = self._shell_args(parsed.command)
        return self._execute_foreground(shell_args, parsed, timeout)

    def _execute_background(self, shell_args: list) -> Dict[str, Any]:
        if self._bg_tracker is None:
            raise CommandError(
                "not_supported",
                "Background execution is not enabled",
            )

        log_file = _BG_LOG_DIR / f"opik-bg-{os.getpid()}-{int(time.time())}.log"
        fh = open(log_file, "w")  # noqa: SIM115

        try:
            proc = subprocess.Popen(
                shell_args,
                cwd=str(self._repo_root),
                stdout=fh,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                start_new_session=True,
            )
        except OSError:
            fh.close()
            raise

        # Rename to include actual PID for easier identification.
        final_log = _BG_LOG_DIR / f"opik-bg-{proc.pid}.log"
        try:
            log_file.rename(final_log)
        except OSError:
            final_log = log_file

        try:
            self._bg_tracker.register(proc)
        except CommandError:
            self._kill_process_group(proc)
            fh.close()
            raise

        # Wait briefly to capture startup output and detect immediate crashes.
        time.sleep(self._bg_startup_wait)
        fh.flush()
        fh.close()

        initial_output = ""
        try:
            initial_output = final_log.read_text(errors="replace")[:_MAX_OUTPUT_BYTES]
        except OSError:
            pass

        result: Dict[str, Any] = {
            "pid": proc.pid,
            "status": "running",
            "log_file": str(final_log),
            "initial_output": initial_output,
        }

        exit_code = proc.poll()
        if exit_code is not None:
            result["status"] = "exited"
            result["exit_code"] = exit_code

        return result

    def _execute_foreground(
        self, shell_args: list, parsed: ExecArgs, timeout: float
    ) -> Dict[str, Any]:
        cmd_timeout = min(parsed.timeout or _DEFAULT_TIMEOUT_SECONDS, timeout)

        proc = subprocess.Popen(
            shell_args,
            cwd=str(self._repo_root),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        try:
            stdout, stderr = proc.communicate(timeout=cmd_timeout)
        except subprocess.TimeoutExpired:
            self._kill_process_group(proc)
            raise CommandError(
                "timeout",
                f"Command timed out after {cmd_timeout}s",
            )

        stdout_text, stdout_truncated = self._truncate(stdout)
        stderr_text, stderr_truncated = self._truncate(stderr)

        return {
            "stdout": stdout_text,
            "stderr": stderr_text,
            "exit_code": proc.returncode,
            "truncated": stdout_truncated or stderr_truncated,
        }

    @staticmethod
    def _kill_process_group(proc: subprocess.Popen) -> None:
        try:
            if platform.system() == "Windows":
                proc.kill()
            else:
                os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            pass
        proc.wait()

    @staticmethod
    def _shell_args(command: str, *, line_buffered: bool = False) -> list:
        if platform.system() == "Windows":
            return ["cmd", "/c", command]
        if line_buffered and _has_stdbuf():
            # Force line-buffered stdout so log files get output promptly
            # instead of waiting for the default ~4KB block buffer to fill.
            return ["stdbuf", "-oL", "bash", "-c", command]
        return ["bash", "-c", command]

    @staticmethod
    def _truncate(data: bytes) -> tuple:
        if len(data) <= _MAX_OUTPUT_BYTES:
            return data.decode("utf-8", errors="replace"), False
        truncated = data[:_MAX_OUTPUT_BYTES]
        while truncated and (truncated[-1] & 0xC0) == 0x80:
            truncated = truncated[:-1]
        if truncated and truncated[-1] & 0x80:
            truncated = truncated[:-1]
        return truncated.decode("utf-8", errors="replace"), True
