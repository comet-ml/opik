"""write_file bridge command handler."""

import difflib
from pathlib import Path
from typing import Any, Dict, Optional

from . import FileMutationQueue
from .common import revalidate_path, validate_path


class WriteFileHandler:
    def __init__(self, repo_root: Path, mutation_queue: FileMutationQueue) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        path = validate_path(args.get("path", ""), self._repo_root)
        content = args.get("content", "")

        path.parent.mkdir(parents=True, exist_ok=True)

        with self._mutation_queue.lock(path):
            revalidate_path(path, self._repo_root)

            old_content: Optional[str] = None
            if path.exists():
                try:
                    old_content = path.read_text(encoding="utf-8")
                except (UnicodeDecodeError, OSError):
                    old_content = None

            path.write_text(content, encoding="utf-8")

        diff: Optional[str] = None
        if old_content is not None:
            rel = str(path.relative_to(self._repo_root))
            old_lines = old_content.splitlines(keepends=True)
            new_lines = content.splitlines(keepends=True)
            diff = "".join(
                difflib.unified_diff(
                    old_lines,
                    new_lines,
                    fromfile=f"a/{rel}",
                    tofile=f"b/{rel}",
                    n=4,
                )
            )

        return {
            "bytes_written": len(content.encode("utf-8")),
            "created": old_content is None,
            "diff": diff,
        }
