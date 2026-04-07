"""write_file bridge command handler."""

import difflib
from pathlib import Path
from typing import Any, Dict, Optional

from pydantic import BaseModel

from . import BaseHandler, CommandError, FileLockRegistry
from . import common


class WriteFileArgs(BaseModel):
    path: str
    content: str = ""


class WriteFileHandler(BaseHandler):
    def __init__(self, repo_root: Path, mutation_queue: FileLockRegistry) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = WriteFileArgs(**args)
        path = common.validate_path(parsed.path, self._repo_root)

        try:
            path.parent.mkdir(parents=True, exist_ok=True)
        except PermissionError:
            raise CommandError(
                "permission_denied",
                f"Cannot create parent directory for: {parsed.path}",
            )

        with self._mutation_queue.lock(path):
            common.revalidate_path(path, self._repo_root)

            old_content: Optional[str] = None
            if path.exists():
                try:
                    old_content = path.read_text(encoding="utf-8")
                except (UnicodeDecodeError, OSError):
                    old_content = None

            try:
                path.write_text(parsed.content, encoding="utf-8")
            except PermissionError:
                raise CommandError(
                    "permission_denied",
                    f"File is not writable: {parsed.path}",
                )

        diff: Optional[str] = None
        if old_content is not None:
            rel = str(path.relative_to(self._repo_root))
            old_lines = old_content.splitlines(keepends=True)
            new_lines = parsed.content.splitlines(keepends=True)
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
            "bytes_written": len(parsed.content.encode("utf-8")),
            "created": old_content is None,
            "diff": diff,
        }
