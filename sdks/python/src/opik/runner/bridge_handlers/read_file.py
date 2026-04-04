"""read_file bridge command handler."""

from pathlib import Path
from typing import Any, Dict

from . import CommandError
from .path_utils import is_binary, validate_path

_MAX_LINES = 2000
_MAX_BYTES = 512 * 1024


class ReadFileHandler:
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        path = validate_path(args.get("path", ""), self._repo_root)

        if not path.exists():
            raise CommandError(
                "file_not_found", f"File not found: {args.get('path', '')}"
            )

        if is_binary(path):
            raise CommandError("binary_file", f"Binary file: {args.get('path', '')}")

        offset = int(args.get("offset", 0))
        limit = int(args.get("limit", _MAX_LINES))
        limit = min(limit, _MAX_LINES)

        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            raise CommandError(
                "binary_file", f"File is not valid UTF-8: {args.get('path', '')}"
            )

        lines = text.splitlines(keepends=True)
        total_lines = len(lines)

        if offset > total_lines:
            offset = total_lines

        selected = lines[offset : offset + limit]

        content = "".join(selected)
        truncated = False

        if len(selected) < total_lines - offset:
            truncated = True

        if len(content.encode("utf-8")) > _MAX_BYTES:
            truncated = True
            encoded = content.encode("utf-8")[:_MAX_BYTES]
            content = encoded.decode("utf-8", errors="ignore")

        return {
            "content": content,
            "total_lines": total_lines,
            "truncated": truncated,
            "encoding": "utf-8",
        }
