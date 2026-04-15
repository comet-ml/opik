from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.list_files import ListFilesHandler


class TestListFiles:
    def _handler(self, tmp_path: Path) -> ListFilesHandler:
        return ListFilesHandler(tmp_path)

    def test_list_files__default_args__returns_immediate_contents(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert sorted(result["files"]) == ["a.py", "b.txt"]

    def test_list_files__directory_entry__has_trailing_slash(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "file.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert "src/" in result["files"]
        assert "file.py" in result["files"]

    def test_list_files__default_depth__does_not_recurse(self, tmp_path: Path) -> None:
        (tmp_path / "sub").mkdir()
        (tmp_path / "sub" / "nested.py").write_text("x")
        (tmp_path / "top.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert "top.py" in result["files"]
        assert "sub/" in result["files"]
        assert not any("nested" in f for f in result["files"])

    def test_list_files__hidden_files__included_in_results(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / ".hidden").write_text("x")
        (tmp_path / "visible.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert ".hidden" in result["files"]
        assert "visible.py" in result["files"]

    def test_list_files__noise_directories__excluded_from_results(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "node_modules").mkdir()
        (tmp_path / "__pycache__").mkdir()
        (tmp_path / "src").mkdir()
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert result["files"] == ["src/"]

    def test_list_files__path_arg__scopes_to_subdirectory(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "a.py").write_text("x")
        (tmp_path / "other.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "src"}, timeout=30.0)
        assert "a.py" in result["files"]
        assert "other.py" not in result["files"]

    def test_list_files__path_traversal__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_list_files__nonexistent_path__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "nope"}, timeout=30.0)
        assert exc_info.value.code == "file_not_found"

    def test_list_files__filename_pattern__filters_matching_entries(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        (tmp_path / "c.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert sorted(result["files"]) == ["a.py", "c.py"]

    def test_list_files__pattern_with_path__filters_within_subdir(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "app.py").write_text("x")
        (tmp_path / "src" / "readme.md").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"path": "src", "pattern": "*.py"}, timeout=30.0)
        assert result["files"] == ["app.py"]

    def test_list_files__path_pattern_with_depth__matches_relative_path(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "app.py").write_text("x")
        (tmp_path / "src" / "util.js").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "src/*.py", "depth": 2}, timeout=30.0)
        assert "src/app.py" in result["files"]
        assert not any("util" in f for f in result["files"])

    def test_list_files__empty_directory__returns_empty(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert result["files"] == []
        assert result["total"] == 0
        assert result["truncated"] is False

    def test_list_files__depth_2__includes_one_nested_level(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "app.py").write_text("x")
        (tmp_path / "src" / "lib").mkdir()
        (tmp_path / "src" / "lib" / "deep.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"depth": 2}, timeout=30.0)
        assert "src/app.py" in result["files"]
        assert "src/lib/" in result["files"]
        assert "src/lib/deep.py" not in result["files"]

    def test_list_files__depth_exceeds_max__raises_validation_error(
        self, tmp_path: Path
    ) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(Exception):
            handler.execute({"depth": 10}, timeout=30.0)
