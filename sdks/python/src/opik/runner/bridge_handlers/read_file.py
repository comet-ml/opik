"""read_file bridge command handler."""

from pathlib import Path
from typing import Any, Dict

from pydantic import BaseModel

from . import BaseHandler
from . import common


class ReadFileArgs(BaseModel):
    path: str
    offset: int = 0
    limit: int = 2000


_MAX_LINES = 2000
_MAX_TOKENS = 128_000
_CHARS_PER_TOKEN = 4


class ReadFileHandler(BaseHandler):
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = ReadFileArgs(**args)
        _, text = common.resolve_text_file(parsed.path, self._repo_root)

        offset = parsed.offset
        limit = min(parsed.limit, _MAX_LINES)

        lines = text.splitlines(keepends=True)
        total_lines = len(lines)

        if offset > total_lines:
            offset = total_lines

        selected = lines[offset : offset + limit]

        content = "".join(selected)
        truncated = False

        if len(selected) < total_lines - offset:
            truncated = True

        max_chars = _MAX_TOKENS * _CHARS_PER_TOKEN
        if len(content) > max_chars:
            truncated = True
            content = content[:max_chars]

        return {
            "content": content,
            "total_lines": total_lines,
            "truncated": truncated,
            "encoding": "utf-8",
        }
