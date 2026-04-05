"""list_files bridge command handler."""

import os
from pathlib import Path, PurePosixPath
from typing import Any, Dict, List, Set, Tuple

from pydantic import BaseModel

from . import BaseHandler, CommandError
from . import common


class ListFilesArgs(BaseModel):
    pattern: str = "**/*"
    path: str = ""


_MAX_ENTRIES = 1000
_MAX_BYTES = 512 * 1024


class ListFilesHandler(BaseHandler):
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = ListFilesArgs(**args)
        pattern = parsed.pattern or "**/*"
        sub_path = parsed.path

        if ".." in pattern.split("/"):
            raise CommandError("path_traversal", "Pattern cannot contain '..'")

        if sub_path:
            base = common.validate_path(sub_path, self._repo_root)
        else:
            base = self._repo_root

        if not base.is_dir():
            raise CommandError("file_not_found", f"Directory not found: {sub_path}")

        walk_truncated = False
        all_files = common.git_ls_files(self._repo_root)
        if all_files is None:
            all_files, walk_truncated = _walk_files(self._repo_root)

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
        truncated = walk_truncated

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


_WALK_MAX_FILES = 10_000


def _walk_files(repo_root: Path) -> Tuple[Set[str], bool]:
    files: Set[str] = set()
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [
            d
            for d in dirnames
            if not d.startswith(".") and d not in common.WALK_SKIP_DIRS
        ]
        for fname in filenames:
            if fname.startswith("."):
                continue
            full = Path(dirpath) / fname
            if full.is_symlink():
                try:
                    full.resolve().relative_to(repo_root.resolve())
                except ValueError:
                    continue
            try:
                rel = str(full.relative_to(repo_root))
                files.add(rel)
            except ValueError:
                continue
            if len(files) >= _WALK_MAX_FILES:
                return files, True
    return files, False


def _safe_mtime(path: Path) -> float:
    try:
        return path.stat().st_mtime
    except OSError:
        return 0.0
