"""list_files bridge command handler."""

from pathlib import Path, PurePosixPath
from typing import Any, Dict, List

from . import CommandError
from .common import git_ls_files, validate_path

_MAX_ENTRIES = 1000
_MAX_BYTES = 512 * 1024


class ListFilesHandler:
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        pattern = args.get("pattern") or "**/*"
        sub_path = args.get("path", "")

        if ".." in pattern.split("/"):
            raise CommandError("path_traversal", "Pattern cannot contain '..'")

        if sub_path:
            base = validate_path(sub_path, self._repo_root)
        else:
            base = self._repo_root

        if not base.is_dir():
            raise CommandError("file_not_found", f"Directory not found: {sub_path}")

        all_files = git_ls_files(self._repo_root)
        if all_files is None:
            all_files = set()

        try:
            base_rel = str(base.relative_to(self._repo_root))
        except ValueError:
            base_rel = ""

        filtered: List[str] = []
        for rel in all_files:
            if base_rel and base_rel != "." and not rel.startswith(base_rel + "/"):
                continue
            if not _matches_pattern(rel, pattern):
                continue
            filtered.append(rel)

        filtered.sort(key=lambda r: _safe_mtime(self._repo_root / r), reverse=True)

        matches: List[str] = []
        total = len(filtered)
        byte_count = 0
        truncated = False

        for rel in filtered:
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


def _matches_pattern(rel: str, pattern: str) -> bool:
    p = PurePosixPath(rel)
    if p.match(pattern):
        return True
    if pattern.startswith("**/"):
        return p.match(pattern[3:])
    return False


def _safe_mtime(path: Path) -> float:
    try:
        return path.stat().st_mtime
    except OSError:
        return 0.0
