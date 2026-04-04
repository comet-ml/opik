from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.search_files import SearchFilesHandler


class TestSearchFiles:
    def _handler(self, tmp_path: Path) -> SearchFilesHandler:
        return SearchFilesHandler(tmp_path)

    def _setup_files(self, tmp_path: Path) -> None:
        (tmp_path / "app.py").write_text(
            "def hello():\n    return 'world'\n\ndef goodbye():\n    pass\n"
        )
        (tmp_path / "lib.py").write_text("import os\nimport sys\n")
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "deep.py").write_text("def deep_func():\n    pass\n")

    def test_regex__matches(self, tmp_path: Path) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": r"def \w+"}, timeout=30.0)
        assert result["total_matches"] >= 2
        assert any(m["file"] == "app.py" for m in result["matches"])

    def test_context_lines(self, tmp_path: Path) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "return"}, timeout=30.0)
        match = result["matches"][0]
        assert "context_before" in match
        assert "context_after" in match
        assert len(match["context_before"]) <= 3
        assert len(match["context_after"]) <= 3

    def test_long_line_truncated(self, tmp_path: Path) -> None:
        (tmp_path / "long.py").write_text("x" * 1000 + " MARKER\n")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "MARKER"}, timeout=30.0)
        # Line too long, MARKER is past 500 chars — match still found
        assert result["total_matches"] == 1
        assert len(result["matches"][0]["content"]) <= 500

    def test_glob_filter(self, tmp_path: Path) -> None:
        (tmp_path / "a.py").write_text("target\n")
        (tmp_path / "b.txt").write_text("target\n")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "target", "glob": "*.py"}, timeout=30.0)
        files = [m["file"] for m in result["matches"]]
        assert "a.py" in files
        assert "b.txt" not in files

    def test_truncates_at_100(self, tmp_path: Path) -> None:
        for i in range(30):
            (tmp_path / f"f{i}.py").write_text(
                "\n".join(f"match_{j}" for j in range(10))
            )
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "match_"}, timeout=30.0)
        assert len(result["matches"]) <= 100
        assert result["total_matches"] == 300
        assert result["truncated"] is True

    def test_binary_skipped(self, tmp_path: Path) -> None:
        (tmp_path / "bin.dat").write_bytes(b"\x00target\x00")
        (tmp_path / "text.py").write_text("target\n")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "target"}, timeout=30.0)
        files = [m["file"] for m in result["matches"]]
        assert "text.py" in files
        assert "bin.dat" not in files

    def test_scoped_to_subdir(self, tmp_path: Path) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "def", "path": "src"}, timeout=30.0)
        files = [m["file"] for m in result["matches"]]
        assert all("src/" in f or "src\\" in f for f in files)

    def test_path_traversal__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "x", "path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_no_matches(self, tmp_path: Path) -> None:
        (tmp_path / "file.py").write_text("nothing here\n")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "ZZZZZ"}, timeout=30.0)
        assert result["matches"] == []
        assert result["total_matches"] == 0

    def test_invalid_regex__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "[invalid"}, timeout=30.0)
        assert exc_info.value.code == "match_not_found"

    def test_empty_pattern__error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": ""}, timeout=30.0)
        assert exc_info.value.code == "match_not_found"
