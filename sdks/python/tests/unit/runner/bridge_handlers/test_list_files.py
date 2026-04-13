import subprocess
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.list_files import ListFilesHandler


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
        ["git", "commit", "-m", "init"],
        cwd=str(tmp_path),
        capture_output=True,
    )


class TestListFiles:
    def _handler(self, tmp_path: Path) -> ListFilesHandler:
        return ListFilesHandler(tmp_path)

    def test_list_files__pattern_filter__matches_only_matching(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "b.txt").write_text("x")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert "a.py" in result["files"]
        assert "b.txt" not in result["files"]

    def test_list_files__recursive_glob__finds_nested_files(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "deep.py").write_text("x")
        (tmp_path / "top.py").write_text("x")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        files = result["files"]
        assert any("deep.py" in f for f in files)
        assert any("top.py" in f for f in files)

    def test_list_files__multiple_files__sorted_by_mtime(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        (tmp_path / "old.py").write_text("x")
        time.sleep(0.1)
        (tmp_path / "new.py").write_text("x")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert result["files"][0] == "new.py"

    def test_list_files__nested_file__returns_relative_paths(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "sub").mkdir()
        (tmp_path / "sub" / "file.py").write_text("x")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        assert any(f.startswith("sub/") for f in result["files"])

    def test_list_files__subdir_scope__excludes_other_dirs(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "a.py").write_text("x")
        (tmp_path / "other.py").write_text("x")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.py", "path": "src"}, timeout=30.0)
        files = result["files"]
        assert any("a.py" in f for f in files)
        assert not any("other.py" == f for f in files)

    def test_list_files__path_traversal__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "*.py", "path": "../../"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_list_files__no_matches__returns_empty(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "*.xyz"}, timeout=30.0)
        assert result["files"] == []
        assert result["total"] == 0
        assert result["truncated"] is False

    @patch("subprocess.run")
    def test_list_files__not_a_git_repo__raises_error(
        self, mock_run: MagicMock, tmp_path: Path
    ) -> None:
        mock_run.return_value = MagicMock(returncode=1)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert exc_info.value.code == "not_a_git_repository"

    @patch("subprocess.run")
    def test_list_files__git_not_available__raises_error(
        self, mock_run: MagicMock, tmp_path: Path
    ) -> None:
        mock_run.side_effect = FileNotFoundError("git not found")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "*.py"}, timeout=30.0)
        assert exc_info.value.code == "git_not_available"
