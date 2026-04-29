from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError, FileLockRegistry
from opik.runner.bridge_handlers.write_file import WriteFileHandler


class TestWriteFile:
    def _handler(self, tmp_path: Path) -> WriteFileHandler:
        return WriteFileHandler(tmp_path, FileLockRegistry())

    def test_write_file__new_file__creates_file(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "new.py", "content": "hello\n"}, timeout=30.0)
        assert result["created"] is True
        assert result["bytes_written"] == 6
        assert result["diff"] is None
        assert (tmp_path / "new.py").read_text() == "hello\n"

    def test_write_file__nested_path__creates_parent_dirs(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "deep/nested/file.py", "content": "x"}, timeout=30.0
        )
        assert result["created"] is True
        assert (tmp_path / "deep" / "nested" / "file.py").read_text() == "x"

    def test_write_file__existing_file__overwrites_with_diff(
        self, tmp_path: Path
    ) -> None:
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

    def test_write_file__existing_file__returns_valid_unified_diff(
        self, tmp_path: Path
    ) -> None:
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

    def test_write_file__path_traversal__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "../../evil.py", "content": "bad"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_write_file__utf8_content__reports_correct_bytes(
        self, tmp_path: Path
    ) -> None:
        handler = self._handler(tmp_path)
        content = "café ☕"
        result = handler.execute({"path": "utf.py", "content": content}, timeout=30.0)
        assert result["bytes_written"] == len(content.encode("utf-8"))

    def test_write_file__same_content__returns_empty_diff(self, tmp_path: Path) -> None:
        f = tmp_path / "same.py"
        f.write_text("unchanged\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"path": "same.py", "content": "unchanged\n"}, timeout=30.0
        )
        assert result["diff"] == ""

    def test_write_file__readonly_file__raises_permission_denied(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "readonly.py"
        f.write_text("original\n")
        f.chmod(0o444)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "readonly.py", "content": "new\n"}, timeout=30.0)
        assert exc_info.value.code == "permission_denied"
        f.chmod(0o644)

    def test_write_file__readonly_parent__raises_permission_denied(
        self, tmp_path: Path
    ) -> None:
        ro_dir = tmp_path / "locked"
        ro_dir.mkdir()
        ro_dir.chmod(0o444)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "locked/sub/new.py", "content": "x"}, timeout=30.0)
        assert exc_info.value.code == "permission_denied"
        ro_dir.chmod(0o755)
