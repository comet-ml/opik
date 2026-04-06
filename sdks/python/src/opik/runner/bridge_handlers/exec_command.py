"""exec bridge command handler — runs shell commands in the project root."""

import os
import platform
import re
import signal
import subprocess
from pathlib import Path
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field

from . import BaseHandler, CommandError

_MAX_OUTPUT_BYTES = 512 * 1024
_DEFAULT_TIMEOUT = 30
_MAX_TIMEOUT = 120

_BLOCKLIST = [
    re.compile(r"\bsudo\b"),
    re.compile(r"\bsu\b"),
    re.compile(r"\bdoas\b"),
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
    timeout: Optional[int] = Field(default=None, ge=1, le=_MAX_TIMEOUT)


class ExecHandler(BaseHandler):
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = ExecArgs(**args)

        if not parsed.command.strip():
            raise CommandError("invalid_command", "Empty command")

        for pattern in _BLOCKLIST:
            if pattern.search(parsed.command):
                raise CommandError("blocked", "Command blocked by safety filter")

        cmd_timeout = min(parsed.timeout or _DEFAULT_TIMEOUT, timeout)
        shell_args = self._shell_args(parsed.command)

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
    def _shell_args(command: str) -> list:
        if platform.system() == "Windows":
            return ["cmd", "/c", command]
        return ["bash", "-c", command]

    @staticmethod
    def _truncate(data: bytes) -> tuple:
        if len(data) <= _MAX_OUTPUT_BYTES:
            return data.decode("utf-8", errors="replace"), False
        truncated = data[:_MAX_OUTPUT_BYTES]
        # Avoid splitting a multi-byte UTF-8 character at the boundary
        while truncated and (truncated[-1] & 0xC0) == 0x80:
            truncated = truncated[:-1]
        if truncated and truncated[-1] & 0x80:
            truncated = truncated[:-1]
        return truncated.decode("utf-8", errors="replace"), True
