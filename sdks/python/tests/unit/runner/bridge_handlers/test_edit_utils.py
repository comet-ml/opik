import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.edit_utils import (
    MatchResult,
    apply_edits,
    detect_line_ending,
    find_match,
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


class TestFindMatch:
    def test_exact__returns_position(self) -> None:
        match = find_match("hello world", "world")
        assert match is not None
        assert match.pos == 6
        assert match.length == 5
        assert match.fuzzy is False

    def test_fuzzy_fallback__returns_position(self) -> None:
        content = "say \u201chello\u201d"
        match = find_match(content, 'say "hello"')
        assert match is not None
        assert match.fuzzy is True

    def test_not_found__returns_none(self) -> None:
        match = find_match("hello world", "xyz")
        assert match is None

    def test_multiple__raises(self) -> None:
        with pytest.raises(CommandError) as exc_info:
            find_match("ab ab ab", "ab")
        assert exc_info.value.code == "match_ambiguous"


class TestValidateEdits:
    def test_no_change__raises(self) -> None:
        m = MatchResult(pos=0, length=5, fuzzy=False)
        with pytest.raises(CommandError) as exc_info:
            validate_edits([(m, "hello", "hello")])
        assert exc_info.value.code == "no_change"

    def test_overlap__raises(self) -> None:
        m1 = MatchResult(pos=0, length=10, fuzzy=False)
        m2 = MatchResult(pos=5, length=10, fuzzy=False)
        with pytest.raises(CommandError) as exc_info:
            validate_edits([(m1, "aaaaaaaaaa", "b"), (m2, "aaaaaaaaaa", "c")])
        assert exc_info.value.code == "edits_overlap"

    def test_valid__no_error(self) -> None:
        m1 = MatchResult(pos=0, length=5, fuzzy=False)
        m2 = MatchResult(pos=10, length=5, fuzzy=False)
        validate_edits([(m1, "hello", "world"), (m2, "foo12", "bar34")])


class TestApplyEdits:
    def test_single_edit(self) -> None:
        m = MatchResult(pos=6, length=5, fuzzy=False)
        result = apply_edits("hello world", [(m, "earth")])
        assert result == "hello earth"

    def test_multiple_reverse_order(self) -> None:
        content = "aaa bbb ccc"
        m1 = MatchResult(pos=0, length=3, fuzzy=False)
        m2 = MatchResult(pos=8, length=3, fuzzy=False)
        result = apply_edits(content, [(m1, "AAA"), (m2, "CCC")])
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
