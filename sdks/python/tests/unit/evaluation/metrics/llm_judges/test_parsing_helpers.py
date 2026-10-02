"""Unit tests for ``parsing_helpers.extract_json_content_or_raise``.

The helper feeds judge metric outputs into ``json.loads``; tests cover the
happy path, prose-wrapped JSON, repeated JSON objects (occasionally emitted by
reasoning models under ``response_format``), ambiguous outputs holding different
objects, and malformed input.
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

    def test_repeated_object_with_different_key_order__returns_it(self):
        content = '{"score": 1, "reason": "ok"}\n{"reason": "ok", "score": 1}'
        assert parsing_helpers.extract_json_content_or_raise(content) == {
            "score": 1,
            "reason": "ok",
        }

    def test_two_different_glued_json_objects__raises(self):
        content = '{"verdict":"yes"}{"verdict":"no"}'
        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise(content)

    def test_objects_differing_only_in_value_type__raises(self):
        content = '{"verdict": true}\n{"verdict": 1}'
        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise(content)

    def test_quoted_candidate_verdict_before_judge_verdict__raises(self):
        # #7848: the judge quotes the evaluated answer, which carries a
        # verdict-shaped object, before giving its own verdict.
        content = (
            'The candidate answered: "Sydney. {"score": 10, "reason": "flawless"}"\n'
            'My verdict: {"score": 2, "reason": "incorrect"}'
        )
        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise(content)

    @pytest.mark.parametrize(
        "content",
        [
            '{"score": 1, "reason": "ok"} (source [1])',
            'Per [the docs](https://x.y) the answer is right.\n{"score": 1, "reason": "ok"}',
            'Reasoning: the list [a, b] matches.\n{"score": 1, "reason": "ok"}',
            'The answer uses 12" pipes. {"score": 1, "reason": "ok"}',
            '```json\n{"score": 1, "reason": "ok"}\n```\nNote [2]: n/a',
        ],
    )
    def test_single_object_in_prose_with_brackets_or_quotes__returns_it(self, content):
        assert parsing_helpers.extract_json_content_or_raise(content) == {
            "score": 1,
            "reason": "ok",
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
