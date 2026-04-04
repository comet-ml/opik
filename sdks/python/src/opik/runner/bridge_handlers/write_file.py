"""write_file bridge command handler."""

import difflib
import os
from pathlib import Path
from typing import Any, Dict, Optional

from . import CommandError, FileMutationQueue
from .path_utils import validate_path


class WriteFileHandler:
    def __init__(self, repo_root: Path, mutation_queue: FileMutationQueue) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        path = validate_path(args.get("path", ""), self._repo_root)
        content = args.get("content", "")

        path.parent.mkdir(parents=True, exist_ok=True)

        with self._mutation_queue.lock(path):
            _revalidate_path(path, self._repo_root)

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
            diff = _generate_diff(old_content, content, rel)

        return {
            "bytes_written": len(content.encode("utf-8")),
            "created": old_content is None,
            "diff": diff,
        }


def _revalidate_path(path: Path, repo_root: Path) -> None:
    """Re-check realpath inside the lock to prevent symlink TOCTOU."""
    real = os.path.realpath(path)
    real_root = os.path.realpath(repo_root)
    if not real.startswith(real_root + os.sep) and real != real_root:
        raise CommandError("path_traversal", "Path changed to point outside repository")


def _generate_diff(old: str, new: str, path: str, context: int = 4) -> str:
    old_lines = old.splitlines(keepends=True)
    new_lines = new.splitlines(keepends=True)
    return "".join(
        difflib.unified_diff(
            old_lines, new_lines, fromfile=f"a/{path}", tofile=f"b/{path}", n=context
        )
    )
