from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.list_files import ListFilesHandler


class TestListFiles:
    def _handler(self, tmp_path: Path) -> ListFilesHandler:
        return ListFilesHandler(tmp_path)

    def test_matches_pattern(self, tmp_path: Path) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert "a.py" in result["files"]
        assert "b.txt" not in result["files"]

    def test_recursive_glob(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "deep.py").write_text("x")
        (tmp_path / "top.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        files = result["files"]
        assert any("deep.py" in f for f in files)
        assert any("top.py" in f for f in files)

    def test_sorted_by_mtime(self, tmp_path: Path) -> None:
        import time

        (tmp_path / "old.py").write_text("x")
        time.sleep(0.1)
        (tmp_path / "new.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert result["files"][0] == "new.py"

    def test_truncates_at_1000(self, tmp_path: Path) -> None:
        for i in range(1100):
            (tmp_path / f"f{i:04d}.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert len(result["files"]) == 1000
        assert result["total"] == 1100
        assert result["truncated"] is True

    def test_relative_paths(self, tmp_path: Path) -> None:
        (tmp_path / "sub").mkdir()
        (tmp_path / "sub" / "file.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        assert result["files"][0].startswith("sub/") or result["files"][0].startswith(
            "sub\\"
        )

    def test_scoped_to_subdir(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "a.py").write_text("x")
        (tmp_path / "other.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py", "path": "src"}, timeout=30.0)
        files = result["files"]
        assert any("a.py" in f for f in files)
        assert not any("other.py" in f for f in files)

    def test_path_traversal__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "*.py", "path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_empty_result(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.xyz"}, timeout=30.0)
        assert result["files"] == []
        assert result["total"] == 0
        assert result["truncated"] is False
