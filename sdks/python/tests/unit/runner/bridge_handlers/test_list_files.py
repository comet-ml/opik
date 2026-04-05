import subprocess
import time
from pathlib import Path

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


class TestListFilesNonGit:
    """Tests for ListFiles fallback when no git repo is present."""

    def _handler(self, tmp_path: Path) -> ListFilesHandler:
        return ListFilesHandler(tmp_path)

    def test_list_files__no_git__finds_files(self, tmp_path: Path) -> None:
        (tmp_path / "a.py").write_text("x")
        (tmp_path / "sub").mkdir()
        (tmp_path / "sub" / "b.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        assert "a.py" in result["files"]
        assert any("b.py" in f for f in result["files"])

    def test_list_files__no_git__skips_hidden_dirs(self, tmp_path: Path) -> None:
        (tmp_path / ".hidden").mkdir()
        (tmp_path / ".hidden" / "secret.py").write_text("x")
        (tmp_path / "visible.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "**/*.py"}, timeout=30.0)
        assert "visible.py" in result["files"]
        assert not any("secret.py" in f for f in result["files"])

    def test_list_files__no_git__skips_hidden_files(self, tmp_path: Path) -> None:
        (tmp_path / ".env").write_text("SECRET=x")
        (tmp_path / "app.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert not any(".env" in f for f in result["files"])
        assert "app.py" in result["files"]

    def test_list_files__no_git__skips_junk_dirs(self, tmp_path: Path) -> None:
        (tmp_path / "node_modules").mkdir()
        (tmp_path / "node_modules" / "pkg.js").write_text("x")
        (tmp_path / "__pycache__").mkdir()
        (tmp_path / "__pycache__" / "mod.pyc").write_text("x")
        (tmp_path / "app.py").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert "app.py" in result["files"]
        assert not any("node_modules" in f for f in result["files"])
        assert not any("__pycache__" in f for f in result["files"])

    def test_list_files__no_git__symlink_outside_root_excluded(
        self, tmp_path: Path
    ) -> None:
        outside = tmp_path / "outside"
        outside.mkdir()
        secret = outside / "secret.txt"
        secret.write_text("sensitive")

        repo = tmp_path / "repo"
        repo.mkdir()
        (repo / "legit.py").write_text("x")
        (repo / "link.txt").symlink_to(secret)

        handler = ListFilesHandler(repo)
        result = handler.execute({}, timeout=30.0)
        assert "legit.py" in result["files"]
        assert not any("link.txt" in f for f in result["files"])

    def test_list_files__no_git__caps_at_max_files(self, tmp_path: Path) -> None:
        from opik.runner.bridge_handlers.list_files import _WALK_MAX_FILES

        for i in range(_WALK_MAX_FILES + 100):
            (tmp_path / f"file_{i:06d}.txt").write_text("x")
        handler = self._handler(tmp_path)
        result = handler.execute({}, timeout=30.0)
        assert result["total"] <= _WALK_MAX_FILES
