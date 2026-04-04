import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.edit_utils import (
    apply_edits,
    detect_line_ending,
    find_exact,
    find_fuzzy,
    fuzzy_normalize,
    generate_diff,
    normalize_to_lf,
    restore_line_ending,
    strip_bom,
    validate_edits,
)


class TestStripBom:
    def test_with_bom(self) -> None:
        text, bom = strip_bom("\ufeffhello")
        assert text == "hello"
        assert bom == "\ufeff"

    def test_without_bom(self) -> None:
        text, bom = strip_bom("hello")
        assert text == "hello"
        assert bom == ""


class TestDetectLineEnding:
    def test_crlf_first(self) -> None:
        assert detect_line_ending("a\r\nb\nc") == "\r\n"

    def test_lf_only(self) -> None:
        assert detect_line_ending("a\nb\n") == "\n"

    def test_no_newlines(self) -> None:
        assert detect_line_ending("abc") == "\n"


class TestNormalize:
    def test_normalize_crlf(self) -> None:
        assert normalize_to_lf("a\r\nb\r\n") == "a\nb\n"

    def test_restore_crlf(self) -> None:
        assert restore_line_ending("a\nb\n", "\r\n") == "a\r\nb\r\n"

    def test_restore_lf(self) -> None:
        assert restore_line_ending("a\nb\n", "\n") == "a\nb\n"


class TestFuzzyNormalize:
    def test_smart_quotes(self) -> None:
        result = fuzzy_normalize("\u201chello\u201d")
        assert result == '"hello"'

    def test_em_dash(self) -> None:
        result = fuzzy_normalize("a\u2014b")
        assert result == "a-b"

    def test_nbsp(self) -> None:
        result = fuzzy_normalize("a\u00a0b")
        assert result == "a b"

    def test_trailing_whitespace(self) -> None:
        result = fuzzy_normalize("hello   \n")
        assert result == "hello\n"

    def test_nfkc(self) -> None:
        result = fuzzy_normalize("\ufb01")  # fi ligature
        assert result == "fi"


class TestFindExact:
    def test_found(self) -> None:
        result = find_exact("hello world", "world")
        assert result == (6, 5)

    def test_not_found(self) -> None:
        result = find_exact("hello world", "xyz")
        assert result is None

    def test_ambiguous(self) -> None:
        with pytest.raises(CommandError) as exc_info:
            find_exact("ab ab ab", "ab")
        assert exc_info.value.code == "match_ambiguous"


class TestFindFuzzy:
    def test_smart_quotes(self) -> None:
        content = fuzzy_normalize("say \u201chello\u201d")
        result = find_fuzzy(content, 'say "hello"')
        assert result is not None

    def test_not_found(self) -> None:
        content = fuzzy_normalize("hello world")
        result = find_fuzzy(content, "xyz")
        assert result is None

    def test_ambiguous(self) -> None:
        content = fuzzy_normalize("ab ab ab")
        with pytest.raises(CommandError) as exc_info:
            find_fuzzy(content, "ab")
        assert exc_info.value.code == "match_ambiguous"


class TestValidateEdits:
    def test_overlap__raises(self) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_edits([(0, 10), (5, 10)])
        assert exc_info.value.code == "edits_overlap"

    def test_valid__no_error(self) -> None:
        validate_edits([(0, 5), (10, 5)])


class TestApplyEdits:
    def test_single_edit(self) -> None:
        result = apply_edits("hello world", [(6, 5, "earth")])
        assert result == "hello earth"

    def test_multiple_reverse_order(self) -> None:
        result = apply_edits("aaa bbb ccc", [(0, 3, "AAA"), (8, 3, "CCC")])
        assert result == "AAA bbb CCC"


class TestGenerateDiff:
    def test_basic(self) -> None:
        diff = generate_diff("hello\n", "world\n", "test.py")
        assert "--- a/test.py" in diff
        assert "+++ b/test.py" in diff
        assert "-hello" in diff
        assert "+world" in diff

    def test_multiple_hunks(self) -> None:
        old = "".join(f"line{i}\n" for i in range(20))
        new_lines = list(f"line{i}\n" for i in range(20))
        new_lines[2] = "CHANGED\n"
        new_lines[17] = "ALSO_CHANGED\n"
        new = "".join(new_lines)
        diff = generate_diff(old, new, "test.py", context=1)
        assert diff.count("@@") == 4  # 2 hunks, each has @@ ... @@
