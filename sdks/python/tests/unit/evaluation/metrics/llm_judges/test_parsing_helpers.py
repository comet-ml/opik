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
