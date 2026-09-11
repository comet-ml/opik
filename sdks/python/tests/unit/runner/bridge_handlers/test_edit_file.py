from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError, FileLockRegistry
from opik.runner.bridge_handlers.edit_file import EditFileHandler


class TestEditFileExact:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__single_exact_match__applies_edit(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("hello world\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "code.py",
                "edits": [{"old_string": "world", "new_string": "earth"}],
            },
            timeout=30.0,
        )
        assert result["edits_applied"] == 1
        assert result["fuzzy_match_used"] is False
        assert f.read_text() == "hello earth\n"
        assert "-world" in result["diff"] or "-hello world" in result["diff"]

    def test_edit_file__multiple_edits__applies_all(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("aaa bbb ccc\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "code.py",
                "edits": [
                    {"old_string": "aaa", "new_string": "AAA"},
                    {"old_string": "ccc", "new_string": "CCC"},
                ],
            },
            timeout=30.0,
        )
        assert result["edits_applied"] == 2
        assert f.read_text() == "AAA bbb CCC\n"

    def test_edit_file__match_not_found__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("hello\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "code.py",
                    "edits": [{"old_string": "xyz", "new_string": "abc"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "match_not_found"

    def test_edit_file__ambiguous_match__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("ab ab ab\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "code.py",
                    "edits": [{"old_string": "ab", "new_string": "cd"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "match_ambiguous"

    def test_edit_file__overlapping_edits__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("hello world foo\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "code.py",
                    "edits": [
                        {"old_string": "hello world", "new_string": "X"},
                        {"old_string": "world foo", "new_string": "Y"},
                    ],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "edits_overlap"

    def test_edit_file__same_old_and_new__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("hello\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "code.py",
                    "edits": [{"old_string": "hello", "new_string": "hello"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "no_change"


class TestEditFileBom:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__bom_file__matches_without_bom(self, tmp_path: Path) -> None:
        f = tmp_path / "bom.py"
        f.write_text("\ufeffhello world\n", encoding="utf-8")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "bom.py",
                "edits": [{"old_string": "hello", "new_string": "hi"}],
            },
            timeout=30.0,
        )
        assert result["edits_applied"] == 1

    def test_edit_file__bom_file_edited__preserves_bom(self, tmp_path: Path) -> None:
        f = tmp_path / "bom.py"
        f.write_text("\ufeffhello\n", encoding="utf-8")
        handler = self._handler(tmp_path)
        handler.execute(
            {
                "path": "bom.py",
                "edits": [{"old_string": "hello", "new_string": "world"}],
            },
            timeout=30.0,
        )
        content = f.read_text(encoding="utf-8")
        assert content.startswith("\ufeff")
        assert "world" in content


class TestEditFileLineEndings:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__crlf_file__matches_with_lf(self, tmp_path: Path) -> None:
        f = tmp_path / "crlf.py"
        f.write_bytes(b"hello\r\nworld\r\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "crlf.py",
                "edits": [{"old_string": "hello\nworld", "new_string": "hi\nearth"}],
            },
            timeout=30.0,
        )
        assert result["edits_applied"] == 1

    def test_edit_file__crlf_file_edited__preserves_crlf(self, tmp_path: Path) -> None:
        f = tmp_path / "crlf.py"
        f.write_bytes(b"hello\r\nworld\r\n")
        handler = self._handler(tmp_path)
        handler.execute(
            {
                "path": "crlf.py",
                "edits": [{"old_string": "hello", "new_string": "hi"}],
            },
            timeout=30.0,
        )
        raw = f.read_bytes()
        assert b"\r\n" in raw
        assert b"hi\r\n" in raw


class TestEditFileFuzzy:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__smart_quotes__uses_fuzzy_match(self, tmp_path: Path) -> None:
        f = tmp_path / "q.py"
        f.write_text("say \u201chello\u201d\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "q.py",
                "edits": [{"old_string": 'say "hello"', "new_string": 'say "world"'}],
            },
            timeout=30.0,
        )
        assert result["fuzzy_match_used"] is True
        assert f.read_text() == 'say "world"\n'

    def test_edit_file__unicode_dash__flags_fuzzy_in_result(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "f.py"
        f.write_text("a\u2014b\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "f.py",
                "edits": [{"old_string": "a-b", "new_string": "a_b"}],
            },
            timeout=30.0,
        )
        assert result["fuzzy_match_used"] is True

    def test_edit_file__fuzzy_match__preserves_unmatched_unicode(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "q.py"
        f.write_text("region_a = \u201chello\u201d\nregion_b = \u201cworld\u201d\n")
        handler = self._handler(tmp_path)
        result = handler.execute(
            {
                "path": "q.py",
                "edits": [{"old_string": '"hello"', "new_string": '"replaced"'}],
            },
            timeout=30.0,
        )
        assert result["fuzzy_match_used"] is True
        content = f.read_text()
        assert '"replaced"' in content
        assert "\u201cworld\u201d" in content


class TestEditFileMultiEdit:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__reverse_order_edits__applies_both_correctly(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "code.py"
        f.write_text("first\nsecond\nthird\n")
        handler = self._handler(tmp_path)
        handler.execute(
            {
                "path": "code.py",
                "edits": [
                    {"old_string": "first", "new_string": "FIRST"},
                    {"old_string": "third", "new_string": "THIRD"},
                ],
            },
            timeout=30.0,
        )
        assert f.read_text() == "FIRST\nsecond\nTHIRD\n"

    def test_edit_file__multiple_edits_same_line__matches_against_original(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "code.py"
        f.write_text("aaa bbb\n")
        handler = self._handler(tmp_path)
        handler.execute(
            {
                "path": "code.py",
                "edits": [
                    {"old_string": "aaa", "new_string": "AAA"},
                    {"old_string": "bbb", "new_string": "BBB"},
                ],
            },
            timeout=30.0,
        )
        assert f.read_text() == "AAA BBB\n"


class TestEditFileEdgeCases:
    def _handler(self, tmp_path: Path) -> EditFileHandler:
        return EditFileHandler(tmp_path, FileLockRegistry())

    def test_edit_file__binary_file__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "bin.dat"
        f.write_bytes(b"\x00\x01")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "bin.dat",
                    "edits": [{"old_string": "x", "new_string": "y"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "binary_file"

    def test_edit_file__file_not_found__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "nope.py",
                    "edits": [{"old_string": "x", "new_string": "y"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "file_not_found"

    def test_edit_file__empty_old_string__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("hello\n")
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "code.py",
                    "edits": [{"old_string": "", "new_string": "x"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "match_not_found"

    def test_edit_file__path_traversal__raises_error(self, tmp_path: Path) -> None:
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "../../etc/passwd",
                    "edits": [{"old_string": "x", "new_string": "y"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "path_traversal"

    def test_edit_file__readonly_file__raises_permission_denied(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "locked.py"
        f.write_text("hello world\n")
        f.chmod(0o444)
        handler = self._handler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute(
                {
                    "path": "locked.py",
                    "edits": [{"old_string": "world", "new_string": "earth"}],
                },
                timeout=30.0,
            )
        assert exc_info.value.code == "permission_denied"
        f.chmod(0o644)
