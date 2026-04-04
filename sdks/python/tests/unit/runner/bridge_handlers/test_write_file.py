from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError, FileMutationQueue
from opik.runner.bridge_handlers.write_file import WriteFileHandler


class TestWriteFile:
    def _handler(self, tmp_path: Path) -> WriteFileHandler:
        return WriteFileHandler(tmp_path, FileMutationQueue())

    def test_new_file__creates(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "new.py", "content": "hello\n"}, timeout=30.0)
        assert result["created"] is True
        assert result["bytes_written"] == 6
        assert result["diff"] is None
        assert (tmp_path / "new.py").read_text() == "hello\n"

    def test_new_file__creates_parent_dirs(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "deep/nested/file.py", "content": "x"}, timeout=30.0
        )
        assert result["created"] is True
        assert (tmp_path / "deep" / "nested" / "file.py").read_text() == "x"

    def test_existing__overwrites_with_diff(self, tmp_path: Path) -> None:
        f = tmp_path / "exist.py"
        f.write_text("old content\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "exist.py", "content": "new content\n"}, timeout=30.0
        )
        assert result["created"] is False
        assert result["diff"] is not None
        assert "-old content" in result["diff"]
        assert "+new content" in result["diff"]
        assert f.read_text() == "new content\n"

    def test_existing__diff_is_valid_unified(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("a\nb\nc\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "code.py", "content": "a\nB\nc\n"}, timeout=30.0
        )
        diff = result["diff"]
        assert diff.startswith("--- a/")
        assert "+++ b/" in diff
        assert "@@" in diff

    def test_path_traversal__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "../../evil.py", "content": "bad"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_sensitive_path__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": ".env", "content": "SECRET=x"}, timeout=30.0)
        assert exc_info.value.code == "sensitive_path"

    def test_bytes_written_correct(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        content = "café ☕"
        result = handler.execute({"path": "utf.py", "content": content}, timeout=30.0)
        assert result["bytes_written"] == len(content.encode("utf-8"))

    def test_same_content__empty_diff(self, tmp_path: Path) -> None:
        f = tmp_path / "same.py"
        f.write_text("unchanged\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "same.py", "content": "unchanged\n"}, timeout=30.0
        )
        assert result["diff"] == ""
