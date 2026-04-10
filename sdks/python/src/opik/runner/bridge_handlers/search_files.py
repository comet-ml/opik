"""search_files bridge command handler."""

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
                text=True,
                timeout=min(timeout, 30.0),
            )
        except subprocess.TimeoutExpired:
            raise CommandError("timeout", "Search timed out")

        if result.returncode not in (0, 1):
            stderr = result.stderr.strip()
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

        for raw_line in result.stdout.splitlines():
            if raw_line == "--":
                _flush_entry()
                current_entry = {}
                context_before = []
                context_after = []
                continue

            sep_idx = raw_line.find("-")
            colon_idx = raw_line.find(":")
            if colon_idx == -1 and sep_idx == -1:
                continue

            is_match = colon_idx != -1 and (sep_idx == -1 or colon_idx < sep_idx)
            if is_match:
                idx = colon_idx
            else:
                idx = sep_idx

            file_part = raw_line[:idx]
            rest = raw_line[idx + 1 :]

            line_sep = rest.find(":" if is_match else "-")
            if line_sep == -1:
                continue

            try:
                line_num = int(rest[:line_sep])
            except ValueError:
                continue
            line_content = rest[line_sep + 1 :][:_MAX_LINE_LENGTH]

            if is_match:
                if current_entry:
                    _flush_entry()
                    context_before = context_after[:]
                    context_after = []
                total_matches += 1
                current_entry = {
                    "file": file_part,
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
