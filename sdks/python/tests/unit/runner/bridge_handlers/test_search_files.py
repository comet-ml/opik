import subprocess
from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.search_files import SearchFilesHandler


def _git_init(tmp_path: Path) -> None:
    subprocess.run(["git", "init"], cwd=str(tmp_path), capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "test@test.com"],
        cwd=str(tmp_path),
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "test"],
        cwd=str(tmp_path),
        capture_output=True,
    )


def _git_add_commit(tmp_path: Path) -> None:
    subprocess.run(["git", "add", "."], cwd=str(tmp_path), capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", "init", "--allow-empty"],
        cwd=str(tmp_path),
        capture_output=True,
    )


class TestSearchFiles:
    def _handler(self, tmp_path: Path) -> SearchFilesHandler:
        return SearchFilesHandler(tmp_path)

    def _setup_files(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        (tmp_path / "app.py").write_text(
            "def hello():\n    return 'world'\n\ndef goodbye():\n    pass\n"
        )
        (tmp_path / "lib.py").write_text("import os\nimport sys\n")
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "deep.py").write_text("def deep_func():\n    pass\n")
        _git_add_commit(tmp_path)

    def test_search_files__regex_pattern__finds_matches(self, tmp_path: Path) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": r"def \w+"}, timeout=30.0)
        assert result["total_matches"] >= 2
        assert any(m["file"] == "app.py" for m in result["matches"])

    def test_search_files__match_found__includes_context_lines(
        self, tmp_path: Path
    ) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "return"}, timeout=30.0)
        match = result["matches"][0]
        assert "context_before" in match
        assert "context_after" in match

    def test_search_files__glob_filter__restricts_to_matching_files(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "a.py").write_text("target\n")
        (tmp_path / "b.txt").write_text("target\n")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "target", "glob": "*.py"}, timeout=30.0)
        files = [m["file"] for m in result["matches"]]
        assert "a.py" in files
        assert "b.txt" not in files

    def test_search_files__subdir_scope__searches_only_subdir(
        self, tmp_path: Path
    ) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "def", "path": "src"}, timeout=30.0)
        files = [m["file"] for m in result["matches"]]
        assert all("src/" in f for f in files)

    def test_search_files__path_traversal__raises_error(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "x", "path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_search_files__no_matches__returns_empty(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        (tmp_path / "file.py").write_text("nothing here\n")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "ZZZZZ"}, timeout=30.0)
        assert result["matches"] == []
        assert result["total_matches"] == 0

    def test_search_files__empty_pattern__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": ""}, timeout=30.0)
        assert exc_info.value.code == "match_not_found"
