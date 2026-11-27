import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.search_files import SearchFilesHandler

_TEST_TIMEOUT = 1.0


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
        result = handler.execute({"pattern": r"def \w+"}, timeout=_TEST_TIMEOUT)
        assert result["total_matches"] >= 2
        assert any(m["file"] == "app.py" for m in result["matches"])

    def test_search_files__match_found__includes_context_lines(
        self, tmp_path: Path
    ) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "return"}, timeout=_TEST_TIMEOUT)
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
        result = handler.execute(
            {"pattern": "target", "glob": "*.py"}, timeout=_TEST_TIMEOUT
        )
        files = [m["file"] for m in result["matches"]]
        assert "a.py" in files
        assert "b.txt" not in files

    def test_search_files__subdir_scope__searches_only_subdir(
        self, tmp_path: Path
    ) -> None:
        self._setup_files(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute(
            {"pattern": "def", "path": "src"}, timeout=_TEST_TIMEOUT
        )
        files = [m["file"] for m in result["matches"]]
        assert all("src/" in f for f in files)

    def test_search_files__path_traversal__raises_error(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "x", "path": "../../"}, timeout=_TEST_TIMEOUT)
        assert exc_info.value.code == "path_traversal"

    def test_search_files__no_matches__returns_empty(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        (tmp_path / "file.py").write_text("nothing here\n")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": "ZZZZZ"}, timeout=_TEST_TIMEOUT)
        assert result["matches"] == []
        assert result["total_matches"] == 0

    def test_search_files__empty_pattern__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": ""}, timeout=_TEST_TIMEOUT)
        assert exc_info.value.code == "match_not_found"

    def test_search_files__not_a_git_repo__raises_error(self, tmp_path: Path) -> None:
        (tmp_path / "file.py").write_text("hello\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"pattern": "hello"}, timeout=_TEST_TIMEOUT)
        assert exc_info.value.code == "not_a_git_repository"

    def test_search_files__untracked_file__is_searched(self, tmp_path: Path) -> None:
        _git_init(tmp_path)
        _git_add_commit(tmp_path)
        # Write after commit so the file is untracked
        (tmp_path / "untracked.py").write_text("def untracked_func():\n    pass\n")
        handler = self._handler(tmp_path)
        result = handler.execute({"pattern": r"def \w+"}, timeout=_TEST_TIMEOUT)
        files = [m["file"] for m in result["matches"]]
        assert "untracked.py" in files

    @patch("subprocess.run")
    def test_search_files__uses_untracked_flag(
        self, mock_run: MagicMock, tmp_path: Path
    ) -> None:
        def side_effect(*args, **kwargs):
            result = MagicMock()
            result.returncode = 0
            result.stdout = b""
            result.stderr = b""
            return result

        mock_run.side_effect = side_effect

        handler = self._handler(tmp_path)
        handler.execute({"pattern": "target"}, timeout=_TEST_TIMEOUT)

        grep_call = [call for call in mock_run.call_args_list if "grep" in call[0][0]][
            0
        ]
        assert "--untracked" in grep_call[0][0]

    @patch("subprocess.run")
    def test_search_files__glob_takes_precedence_over_path(
        self, mock_run: MagicMock, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()

        def side_effect(*args, **kwargs):
            result = MagicMock()
            result.returncode = 0
            result.stdout = b""
            result.stderr = b""
            return result

        mock_run.side_effect = side_effect

        handler = self._handler(tmp_path)
        handler.execute(
            {"pattern": "target", "glob": "*.txt", "path": "src"}, timeout=_TEST_TIMEOUT
        )

        grep_call = [call for call in mock_run.call_args_list if "grep" in call[0][0]][
            0
        ]
        cmd = grep_call[0][0]
        assert "*.txt" in cmd
        assert "src" not in cmd[cmd.index("--") + 1 :]

    @pytest.mark.parametrize(
        "file_name",
        [
            "getting-started.md",
            "2024-11-27-notes.md",
            "a-12-b.md",
            "12-3.txt",
            "dir-one/x-y.md",
        ],
    )
    def test_search_files__hyphen_in_file_name__reports_match_and_context(
        self, tmp_path: Path, file_name: str
    ) -> None:
        _git_init(tmp_path)
        target = tmp_path / file_name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("first\nsecond\nTARGET\nfourth\nfifth\n")
        (tmp_path / "plain.md").write_text("TARGET\n")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)

        result = handler.execute({"pattern": "TARGET"}, timeout=_TEST_TIMEOUT)

        by_file = {m["file"]: m for m in result["matches"]}
        assert result["total_matches"] == 2
        assert by_file[file_name]["line"] == 3
        assert by_file[file_name]["content"] == "TARGET"
        assert by_file[file_name]["context_before"] == ["first", "second"]
        assert by_file[file_name]["context_after"] == ["fourth", "fifth"]

    def test_search_files__line_separator_characters_in_text__keep_file_names(
        self, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        (tmp_path / "a-1.txt").write_bytes(
            b"TARGET one\x0c\n2-x\x0b\r\rq\n\xc2\x85\xe2\x80\xa8TARGET two\n"
        )
        (tmp_path / "b-2.txt").write_bytes(b"x\nTARGET three\n")
        _git_add_commit(tmp_path)
        handler = self._handler(tmp_path)

        result = handler.execute({"pattern": "TARGET"}, timeout=_TEST_TIMEOUT)

        assert [(m["file"], m["line"]) for m in result["matches"]] == [
            ("a-1.txt", 1),
            ("a-1.txt", 3),
            ("b-2.txt", 2),
        ]
        assert result["matches"][0]["content"] == "TARGET one\x0c"

    @patch("opik.runner.bridge_handlers.search_files.subprocess.run")
    def test_search_files__heading_output_with_leading_blank_line__is_parsed(
        self, mock_run: MagicMock, tmp_path: Path
    ) -> None:
        _git_init(tmp_path)
        mock_run.side_effect = lambda *args, **kwargs: MagicMock(
            returncode=0,
            stdout=b"\nfoo-bar.md\n1-a\n2:TARGET\n\nb-1.md\n5:TARGET\n6-c\n",
            stderr=b"",
        )
        handler = self._handler(tmp_path)

        result = handler.execute({"pattern": "TARGET"}, timeout=_TEST_TIMEOUT)

        assert [(m["file"], m["line"]) for m in result["matches"]] == [
            ("foo-bar.md", 2),
            ("b-1.md", 5),
        ]
        assert result["matches"][0]["context_before"] == ["a"]
        assert result["matches"][1]["context_before"] == []
