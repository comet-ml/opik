"""list_files bridge command handler — lists directory contents like ls."""

import fnmatch
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from . import BaseHandler, CommandError
from . import common


_MAX_ENTRIES = 1000
_MAX_BYTES = 512 * 1024
_MAX_DEPTH = 5


class ListFilesArgs(BaseModel):
    path: str = ""
    pattern: Optional[str] = None
    depth: int = Field(default=1, ge=1, le=_MAX_DEPTH)


class ListFilesHandler(BaseHandler):
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = ListFilesArgs(**args)

        if parsed.path:
            base = common.validate_path(parsed.path, self._repo_root)
        else:
            base = self._repo_root

        if not base.is_dir():
            raise CommandError("file_not_found", f"Directory not found: {parsed.path}")

        entries: List[str] = []
        self._collect(base, base, parsed.pattern, parsed.depth, entries)
        entries.sort(key=lambda n: (not n.endswith("/"), n.lower()))

        total = len(entries)
        result_entries: List[str] = []
        byte_count = 0
        truncated = False

        for name in entries:
            entry_bytes = len(name.encode("utf-8")) + 1
            if (
                len(result_entries) >= _MAX_ENTRIES
                or byte_count + entry_bytes > _MAX_BYTES
            ):
                truncated = True
                break
            result_entries.append(name)
            byte_count += entry_bytes

        return {
            "files": result_entries,
            "total": total,
            "truncated": truncated,
        }

    @staticmethod
    def _collect(
        current: Path,
        base: Path,
        pattern: Optional[str],
        depth: int,
        out: List[str],
    ) -> None:
        if depth <= 0:
            return
        try:
            for entry in os.scandir(current):
                is_dir = entry.is_dir(follow_symlinks=False)
                if is_dir and entry.name in common.WALK_SKIP_DIRS:
                    continue
                rel = str(Path(entry.path).relative_to(base))
                if pattern and not is_dir:
                    if not fnmatch.fnmatch(entry.name, pattern) and not fnmatch.fnmatch(
                        rel, pattern
                    ):
                        continue
                if is_dir:
                    out.append(rel + "/")
                    ListFilesHandler._collect(
                        Path(entry.path), base, pattern, depth - 1, out
                    )
                else:
                    out.append(rel)
        except OSError:
            pass
