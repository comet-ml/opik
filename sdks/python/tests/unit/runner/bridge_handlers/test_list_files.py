from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.list_files import ListFilesHandler


class TestListFiles:
    def _handler(self, tmp_path: Path) -> ListFilesHandler:
        return ListFilesHandler(tmp_path)

    def test_lists_immediate_contents(self, tmp_path: Path) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert sorted(result["files"]) == ["a.py", "b.txt"]

    def test_directories_have_trailing_slash(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "file.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert "src/" in result["files"]
        assert "file.py" in result["files"]

    def test_default_depth_does_not_recurse(self, tmp_path: Path) -> None:
        (tmp_path / "sub").mkdir()
        (tmp_path / "sub" / "nested.py").write_text("x")
        (tmp_path / "top.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert "top.py" in result["files"]
        assert "sub/" in result["files"]
        assert not any("nested" in f for f in result["files"])

    def test_hidden_files_included(self, tmp_path: Path) -> None:
        (tmp_path / ".hidden").write_text("x")
        (tmp_path / "visible.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert ".hidden" in result["files"]
        assert "visible.py" in result["files"]

    def test_skip_dirs_excluded(self, tmp_path: Path) -> None:
        (tmp_path / "node_modules").mkdir()
        (tmp_path / "__pycache__").mkdir()
        (tmp_path / "src").mkdir()
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert result["files"] == ["src/"]

    def test_subdir_scope(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "a.py").write_text("x")
        (tmp_path / "other.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "src"}, timeout=30.0)
        assert "a.py" in result["files"]
        assert "other.py" not in result["files"]

    def test_path_traversal_raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_nonexistent_dir_raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "nope"}, timeout=30.0)
        assert exc_info.value.code == "file_not_found"

    def test_pattern_filters_entries(self, tmp_path: Path) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        (tmp_path / "c.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert sorted(result["files"]) == ["a.py", "c.py"]

    def test_pattern_with_path(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "app.py").write_text("x")
        (tmp_path / "src" / "readme.md").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "src", "pattern": "*.py"}, timeout=30.0)
        assert result["files"] == ["app.py"]

    def test_empty_dir_returns_empty(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert result["files"] == []
        assert result["total"] == 0
        assert result["truncated"] is False

    def test_depth_2_finds_nested_files(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "app.py").write_text("x")
        (tmp_path / "src" / "lib").mkdir()
        (tmp_path / "src" / "lib" / "deep.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"depth": 2}, timeout=30.0)
        assert "src/app.py" in result["files"]
        assert "src/lib/" in result["files"]
        assert "src/lib/deep.py" not in result["files"]

    def test_depth_capped_at_max(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(Exception):
            handler.execute({"depth": 10}, timeout=30.0)
