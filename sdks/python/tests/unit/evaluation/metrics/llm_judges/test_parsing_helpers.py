"""Unit tests for ``parsing_helpers.extract_json_content_or_raise``.

The helper feeds judge metric outputs into ``json.loads``; tests cover the
happy path, prose-wrapped JSON, multiple-JSON-object outputs (occasionally
emitted by reasoning models under ``response_format``), and malformed input.
"""

import pytest

from opik import exceptions
from opik.evaluation.metrics.llm_judges import parsing_helpers


class TestExtractJsonContentOrRaise:
    def test_clean_json__returns_parsed_dict(self):
        assert parsing_helpers.extract_json_content_or_raise(
            '{"verdict":"yes","reason":null}'
        ) == {"verdict": "yes", "reason": None}

    def test_json_wrapped_in_prose__falls_back_to_brace_extraction(self):
        content = 'Here you go: {"verdict":"yes","reason":null} done.'
        assert parsing_helpers.extract_json_content_or_raise(content) == {
            "verdict": "yes",
            "reason": None,
        }

    def test_two_glued_json_objects__returns_first_object(self):
        # Real-world case: gpt-5 with reasoning_effort=minimal sometimes
        # emits the same JSON object twice when asked for a structured
        # response. The parser should not blow up — it should surface the
        # first complete object so the metric still produces a verdict.
        content = '{"verdict":"yes","reason":null}\n{"verdict":"yes","reason":null}'
        assert parsing_helpers.extract_json_content_or_raise(content) == {
            "verdict": "yes",
            "reason": None,
        }

    def test_two_different_glued_json_objects__returns_first_object(self):
        content = '{"verdict":"yes"}{"verdict":"no"}'
        assert parsing_helpers.extract_json_content_or_raise(content) == {
            "verdict": "yes"
        }

    def test_no_braces__raises(self):
        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise("not json at all")

    def test_malformed_braces_only__raises(self):
        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise("{not: valid json}")


class TestReasonToText:
    def test_reason_to_text__list_of_reasons__joined_with_newlines(self):
        assert (
            parsing_helpers.reason_to_text(["first reason", "second reason"])
            == "first reason\nsecond reason"
        )

    def test_reason_to_text__single_item_list__no_list_syntax(self):
        assert parsing_helpers.reason_to_text(["only reason"]) == "only reason"

    def test_reason_to_text__string_reason__returned_unchanged(self):
        assert parsing_helpers.reason_to_text("a prose reason") == "a prose reason"

    def test_reason_to_text__non_string_items_in_list__stringified(self):
        assert parsing_helpers.reason_to_text([1, None]) == "1\nNone"

    def test_reason_to_text__empty_list__returns_no_reason_provided(self):
        assert parsing_helpers.reason_to_text([]) == "No reason provided"


class TestNormalizeVerdict:
    def test_normalize_verdict__plain_yes_no__unchanged(self):
        assert parsing_helpers.normalize_verdict("yes") == "yes"
        assert parsing_helpers.normalize_verdict("no") == "no"

    def test_normalize_verdict__case_and_whitespace__folded(self):
        assert parsing_helpers.normalize_verdict("YES") == "yes"
        assert parsing_helpers.normalize_verdict("  No ") == "no"

    def test_normalize_verdict__trailing_punctuation__trimmed(self):
        # Models drift from the "STRICTLY yes/no" prompt; the score
        # computations must not read "No." as a positive verdict.
        assert parsing_helpers.normalize_verdict("Yes.") == "yes"
        assert parsing_helpers.normalize_verdict("No.") == "no"
        assert parsing_helpers.normalize_verdict("no!") == "no"
        assert parsing_helpers.normalize_verdict("yes,") == "yes"

    def test_normalize_verdict__whitespace_before_punctuation__trimmed(self):
        # A space between the verdict and its punctuation must not survive:
        # "No ." would otherwise read as "no " and fail the exact comparison.
        assert parsing_helpers.normalize_verdict("No .") == "no"
        assert parsing_helpers.normalize_verdict("Yes !") == "yes"
        assert parsing_helpers.normalize_verdict("no ;") == "no"

    def test_normalize_verdict__unrecognized_words__left_as_is(self):
        # A genuinely ambiguous verdict stays ambiguous: the caller decides
        # how to count it, the normalizer only removes drift.
        assert parsing_helpers.normalize_verdict("kind of") == "kind of"
