"""search_files bridge command handler."""

import fnmatch
import os
import re
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Set

from . import CommandError
from .path_utils import is_binary, validate_path

_MAX_MATCHES = 100
_MAX_BYTES = 512 * 1024
_MAX_LINE_LENGTH = 500
_CONTEXT_LINES = 3
_MAX_PATTERN_LENGTH = 500
_MAX_FILE_SIZE = 10 * 1024 * 1024


class SearchFilesHandler:
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        pattern_str = args.get("pattern", "")
        if not pattern_str:
            raise CommandError("match_not_found", "Empty search pattern")

        if len(pattern_str) > _MAX_PATTERN_LENGTH:
            raise CommandError(
                "match_not_found", f"Pattern too long (max {_MAX_PATTERN_LENGTH} chars)"
            )

        try:
            regex = re.compile(pattern_str)
        except re.error as e:
            raise CommandError("match_not_found", f"Invalid regex: {e}")

        glob_filter = args.get("glob", "")
        sub_path = args.get("path", "")

        if sub_path:
            base = validate_path(sub_path, self._repo_root)
        else:
            base = self._repo_root

        if not base.is_dir():
            raise CommandError("file_not_found", f"Directory not found: {sub_path}")

        gitignored = self._load_gitignore_set()

        matches: List[Dict[str, Any]] = []
        total_matches = 0
        byte_count = 0
        truncated = False

        for root, dirs, files in os.walk(base):
            dirs[:] = [
                d
                for d in dirs
                if d not in {".git", "__pycache__", ".venv", "node_modules"}
            ]

            for fname in files:
                fpath = Path(root) / fname
                try:
                    rel = str(fpath.relative_to(self._repo_root))
                except ValueError:
                    continue

                if rel in gitignored:
                    continue

                if (
                    glob_filter
                    and not fnmatch.fnmatch(fname, glob_filter)
                    and not fnmatch.fnmatch(rel, glob_filter)
                ):
                    continue

                if is_binary(fpath):
                    continue

                try:
                    size = fpath.stat().st_size
                    if size > _MAX_FILE_SIZE:
                        continue
                    lines = fpath.read_text(encoding="utf-8").splitlines()
                except (UnicodeDecodeError, OSError):
                    continue

                for line_num, line in enumerate(lines):
                    try:
                        if not regex.search(line):
                            continue
                    except RecursionError:
                        break

                    total_matches += 1

                    if len(matches) >= _MAX_MATCHES or byte_count >= _MAX_BYTES:
                        truncated = True
                        continue

                    ctx_start = max(0, line_num - _CONTEXT_LINES)
                    ctx_end = min(len(lines), line_num + _CONTEXT_LINES + 1)

                    match_entry = {
                        "file": rel,
                        "line": line_num + 1,
                        "content": line[:_MAX_LINE_LENGTH],
                        "context_before": [
                            ln[:_MAX_LINE_LENGTH] for ln in lines[ctx_start:line_num]
                        ],
                        "context_after": [
                            ln[:_MAX_LINE_LENGTH]
                            for ln in lines[line_num + 1 : ctx_end]
                        ],
                    }

                    entry_size = len(str(match_entry).encode("utf-8"))
                    if byte_count + entry_size > _MAX_BYTES:
                        truncated = True
                        continue

                    matches.append(match_entry)
                    byte_count += entry_size

        return {
            "matches": matches,
            "total_matches": total_matches,
            "truncated": truncated,
        }

    def _load_gitignore_set(self) -> Set[str]:
        try:
            result = subprocess.run(
                ["git", "ls-files", "--others", "--ignored", "--exclude-standard"],
                cwd=self._repo_root,
                capture_output=True,
                text=True,
                timeout=10,
            )
            if result.returncode == 0:
                return set(result.stdout.strip().splitlines())
        except (OSError, subprocess.TimeoutExpired):
            pass
        return set()
