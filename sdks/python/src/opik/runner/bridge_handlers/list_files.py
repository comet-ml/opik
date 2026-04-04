"""list_files bridge command handler."""

import subprocess
from pathlib import Path
from typing import Any, Dict, List, Set

from . import CommandError
from .path_utils import validate_path

_MAX_ENTRIES = 1000
_MAX_BYTES = 512 * 1024
_EXCLUDED_DIRS = {".git", "__pycache__", ".venv", "node_modules"}


class ListFilesHandler:
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        pattern = args.get("pattern", "**/*")
        sub_path = args.get("path", "")

        if ".." in pattern.split("/"):
            raise CommandError("path_traversal", "Pattern cannot contain '..'")

        if sub_path:
            base = validate_path(sub_path, self._repo_root)
        else:
            base = self._repo_root

        if not base.is_dir():
            raise CommandError("file_not_found", f"Directory not found: {sub_path}")

        gitignored = self._load_gitignore_set()

        matches: List[str] = []
        total = 0
        byte_count = 0
        truncated = False

        for p in sorted(base.glob(pattern), key=_safe_mtime, reverse=True):
            if not p.is_file():
                continue

            if any(part in _EXCLUDED_DIRS for part in p.parts):
                continue

            try:
                rel = str(p.relative_to(self._repo_root))
            except ValueError:
                continue

            if rel in gitignored:
                continue

            total += 1
            entry_bytes = len(rel.encode("utf-8")) + 1

            if len(matches) >= _MAX_ENTRIES or byte_count + entry_bytes > _MAX_BYTES:
                truncated = True
                continue

            matches.append(rel)
            byte_count += entry_bytes

        return {
            "files": matches,
            "total": total,
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


def _safe_mtime(path: Path) -> float:
    try:
        return path.stat().st_mtime
    except OSError:
        return 0.0
