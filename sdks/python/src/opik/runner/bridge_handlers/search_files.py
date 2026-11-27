"""search_files bridge command handler."""

import re
import subprocess
from pathlib import Path
from typing import Any, Dict, List

from pydantic import BaseModel

from . import BaseHandler, CommandError
from . import common


class SearchFilesArgs(BaseModel):
    pattern: str
    glob: str = ""
    path: str = ""


_MAX_MATCHES = 100
_MAX_BYTES = 512 * 1024
_MAX_LINE_LENGTH = 500
_CONTEXT_LINES = 3
_MAX_PATTERN_LENGTH = 500
# A content line under a file heading is `N:text` (match) or `N-text` (context).
_CONTENT_LINE = re.compile(r"(\d+)([:-])")


class SearchFilesHandler(BaseHandler):
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = SearchFilesArgs(**args)

        if not parsed.pattern:
            raise CommandError("match_not_found", "Empty search pattern")

        if len(parsed.pattern) > _MAX_PATTERN_LENGTH:
            raise CommandError(
                "match_not_found",
                f"Pattern too long (max {_MAX_PATTERN_LENGTH} chars)",
            )

        common.check_git_repo(self._repo_root)

        glob_filter = parsed.glob
        sub_path = parsed.path

        if sub_path:
            base = common.validate_path(sub_path, self._repo_root)
            if not base.is_dir():
                raise CommandError("file_not_found", f"Directory not found: {sub_path}")

        cmd = [
            "git",
            "grep",
            "--untracked",
            "-n",
            "--heading",
            "--break",
            f"-C{_CONTEXT_LINES}",
            "--no-color",
            "-P",
            parsed.pattern,
        ]

        # git grep doesn't support combining glob patterns and path arguments.
        # If both are provided, glob takes precedence.
        if glob_filter:
            cmd.extend(["--", glob_filter])
        elif sub_path:
            cmd.extend(["--", sub_path])

        try:
            result = subprocess.run(
                cmd,
                cwd=str(self._repo_root),
                capture_output=True,
                timeout=min(timeout, 30.0),
            )
        except subprocess.TimeoutExpired:
            raise CommandError("timeout", "Search timed out")

        if result.returncode not in (0, 1):
            stderr = result.stderr.decode("utf-8", errors="replace").strip()
            if "invalid" in stderr.lower():
                raise CommandError("match_not_found", f"Invalid pattern: {stderr}")
            raise CommandError("search_failed", f"git grep failed: {stderr}")

        matches: List[Dict[str, Any]] = []
        total_matches = 0
        byte_count = 0
        truncated = False

        current_entry: Dict[str, Any] = {}
        context_before: List[str] = []
        context_after: List[str] = []

        def _flush_entry() -> None:
            nonlocal byte_count, truncated
            if not current_entry:
                return
            current_entry["context_before"] = context_before[:]
            current_entry["context_after"] = context_after[:]
            entry_size = len(str(current_entry).encode("utf-8"))
            if len(matches) < _MAX_MATCHES and byte_count + entry_size <= _MAX_BYTES:
                matches.append(current_entry.copy())
                byte_count += entry_size
            else:
                truncated = True

        # With --heading and --break, git prints each file name on its own line,
        # then its `N:text` / `N-text` lines, and a blank line before the next file.
        # Split the raw bytes on b"\n" only. Text mode would also split a line at
        # a lone "\r", and splitlines() at "\x0c" or U+2028. A blank fragment from
        # such a split would look like the end of a file.
        current_file = ""
        expect_file = True

        for raw_bytes in result.stdout.split(b"\n"):
            raw_line = raw_bytes.removesuffix(b"\r").decode("utf-8", errors="replace")
            if raw_line == "":
                expect_file = True
                continue

            if expect_file or raw_line == "--":
                _flush_entry()
                current_entry = {}
                context_before = []
                context_after = []
                if expect_file:
                    current_file = raw_line
                    expect_file = False
                continue

            content_line = _CONTENT_LINE.match(raw_line)
            if content_line is None:
                continue
            line_num = int(content_line.group(1))
            is_match = content_line.group(2) == ":"
            line_content = raw_line[content_line.end() :][:_MAX_LINE_LENGTH]

            if is_match:
                if current_entry:
                    _flush_entry()
                    context_before = context_after[:]
                    context_after = []
                total_matches += 1
                current_entry = {
                    "file": current_file,
                    "line": line_num,
                    "content": line_content,
                }
            elif current_entry:
                context_after.append(line_content)
            else:
                context_before.append(line_content)

        _flush_entry()

        return {
            "matches": matches,
            "total_matches": total_matches,
            "truncated": truncated,
        }
