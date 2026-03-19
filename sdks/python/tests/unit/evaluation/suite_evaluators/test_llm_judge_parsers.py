import json

import pytest

from opik.evaluation.suite_evaluators.llm_judge import parsers as llm_judge_parsers
from opik.exceptions import LLMJudgeParseError


_INLINED_ASSERTION = {
    "properties": {
        "score": {"type": "boolean"},
        "reason": {"type": "string"},
        "confidence": {"maximum": 1.0, "minimum": 0.0, "type": "number"},
    },
    "required": ["score", "reason", "confidence"],
    "type": "object",
}


class TestResponseSchema:
    def test_response_format__single_assertion__creates_model_with_one_field(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])

        assert "assertion_1" in schema.response_format.model_fields

    def test_response_format__multiple_assertions__creates_model_with_all_fields(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful", "No hallucinations"]
        )

        assert "assertion_1" in schema.response_format.model_fields
        assert "assertion_2" in schema.response_format.model_fields
        assert "assertion_3" in schema.response_format.model_fields
        assert len(schema.response_format.model_fields) == 3

    def test_response_format__descriptions_contain_assertion_text(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "No hallucinations"]
        )
        json_schema = schema.response_format.model_json_schema()

        a1 = json_schema["properties"]["assertion_1"]
        a2 = json_schema["properties"]["assertion_2"]
        assert a1["description"] == "Response is accurate"
        assert a2["description"] == "No hallucinations"

    def test_response_format__validates_valid_input(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])

        instance = schema.response_format(
            **{
                "assertion_1": {
                    "score": True,
                    "reason": "The response is correct",
                    "confidence": 0.95,
                }
            }
        )

        result = getattr(instance, "assertion_1")
        assert result.score is True
        assert result.reason == "The response is correct"
        assert result.confidence == 0.95

    def test_response_format__rejects_missing_field(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful"]
        )

        with pytest.raises(Exception):
            schema.response_format(
                **{
                    "assertion_1": {
                        "score": True,
                        "reason": "Correct",
                        "confidence": 0.9,
                    }
                }
            )

    def test_response_format__keys_are_short_identifiers(self):
        schema = llm_judge_parsers.ResponseSchema(
            [
                "Response is accurate",
                "A very long assertion that would previously create a huge key name",
                'Special chars: {}/\\"quotes"',
            ]
        )
        json_schema = schema.response_format.model_json_schema()

        for prop_name in json_schema["properties"]:
            assert prop_name.isidentifier()
            assert len(prop_name) < 64

    def test_format_assertions__includes_keys_and_text(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "No hallucinations"]
        )

        formatted = schema.format_assertions()

        assert "- `assertion_1`: Response is accurate" in formatted
        assert "- `assertion_2`: No hallucinations" in formatted

    def test_parse__valid_json__returns_score_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = json.dumps(
            {
                "assertion_1": {
                    "score": True,
                    "reason": "The response correctly states Paris",
                    "confidence": 0.95,
                }
            }
        )

        results = schema.parse(content)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value is True
        assert results[0].reason == "The response correctly states Paris"
        assert results[0].scoring_failed is False
        assert results[0].category_name == "suite_assertion"
        assert results[0].metadata == {"confidence": 0.95}

    def test_parse__multiple_assertions__returns_results_in_order(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["First assertion", "Second assertion", "Third assertion"]
        )
        content = json.dumps(
            {
                "assertion_1": {
                    "score": True,
                    "reason": "First reason",
                    "confidence": 0.9,
                },
                "assertion_2": {
                    "score": False,
                    "reason": "Second reason",
                    "confidence": 0.85,
                },
                "assertion_3": {
                    "score": True,
                    "reason": "Third reason",
                    "confidence": 0.7,
                },
            }
        )

        results = schema.parse(content)

        assert len(results) == 3
        assert results[0].name == "First assertion"
        assert results[0].value is True
        assert results[0].metadata == {"confidence": 0.9}
        assert results[1].name == "Second assertion"
        assert results[1].value is False
        assert results[1].metadata == {"confidence": 0.85}
        assert results[2].name == "Third assertion"
        assert results[2].value is True
        assert results[2].metadata == {"confidence": 0.7}

    def test_parse__invalid_json__raises_with_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = "not valid json"

        with pytest.raises(LLMJudgeParseError) as exc_info:
            schema.parse(content)

        results = exc_info.value.results
        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value == 0.0
        assert results[0].scoring_failed is True
        assert results[0].category_name == "suite_assertion"
        assert "Failed to parse model output" in results[0].reason
        assert results[0].metadata["raw_output"] == content

    def test_parse__missing_assertion__raises_with_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful"]
        )
        content = json.dumps(
            {
                "assertion_1": {
                    "score": True,
                    "reason": "Correct",
                    "confidence": 0.9,
                }
            }
        )

        with pytest.raises(LLMJudgeParseError) as exc_info:
            schema.parse(content)

        results = exc_info.value.results
        assert len(results) == 2
        assert all(r.scoring_failed is True for r in results)
        assert all(r.value == 0.0 for r in results)
        assert all(r.category_name == "suite_assertion" for r in results)

    def test_parse__missing_required_field__raises_with_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = json.dumps(
            {
                "assertion_1": {
                    "score": True,
                }
            }
        )

        with pytest.raises(LLMJudgeParseError) as exc_info:
            schema.parse(content)

        results = exc_info.value.results
        assert len(results) == 1
        assert results[0].scoring_failed is True
        assert results[0].value == 0.0
        assert results[0].category_name == "suite_assertion"

    def test_parse__empty_assertions__returns_empty_list(self):
        schema = llm_judge_parsers.ResponseSchema([])

        results = schema.parse("{}")

        assert len(results) == 0

    def test_response_format__many_assertions__creates_all_fields(self):
        assertions = [f"Assertion number {i}" for i in range(1, 11)]
        schema = llm_judge_parsers.ResponseSchema(assertions)

        assert len(schema.response_format.model_fields) == 10
        for i in range(1, 11):
            assert f"assertion_{i}" in schema.response_format.model_fields

    def test_parse__many_assertions__returns_all_results_in_order(self):
        assertions = [f"Assertion number {i}" for i in range(1, 11)]
        schema = llm_judge_parsers.ResponseSchema(assertions)
        content = json.dumps(
            {
                f"assertion_{i}": {
                    "score": i % 2 == 0,
                    "reason": f"Reason for assertion {i}",
                    "confidence": round(0.5 + i * 0.05, 2),
                }
                for i in range(1, 11)
            }
        )

        results = schema.parse(content)

        assert len(results) == 10
        for i, result in enumerate(results, 1):
            assert result.name == f"Assertion number {i}"
            assert result.value is (i % 2 == 0)
            assert result.reason == f"Reason for assertion {i}"
            assert result.scoring_failed is False
            assert result.category_name == "suite_assertion"

    def test_format_assertions__many_assertions__lists_all(self):
        assertions = [f"Check item {i}" for i in range(1, 8)]
        schema = llm_judge_parsers.ResponseSchema(assertions)

        formatted = schema.format_assertions()

        for i in range(1, 8):
            assert f"- `assertion_{i}`: Check item {i}" in formatted

    def test_json_schema__single_assertion__matches_expected_structure(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is factually accurate"])

        assert schema.response_format.model_json_schema() == {
            "properties": {
                "assertion_1": {
                    **_INLINED_ASSERTION,
                    "description": "Response is factually accurate",
                },
            },
            "required": ["assertion_1"],
            "type": "object",
        }

    def test_json_schema__multiple_assertions__matches_expected_structure(self):
        schema = llm_judge_parsers.ResponseSchema(
            [
                "Response is factually accurate",
                "Response does not contain hallucinations",
                "Response directly answers the user's question",
            ]
        )

        assert schema.response_format.model_json_schema() == {
            "properties": {
                "assertion_1": {
                    **_INLINED_ASSERTION,
                    "description": "Response is factually accurate",
                },
                "assertion_2": {
                    **_INLINED_ASSERTION,
                    "description": "Response does not contain hallucinations",
                },
                "assertion_3": {
                    **_INLINED_ASSERTION,
                    "description": "Response directly answers the user's question",
                },
            },
            "required": ["assertion_1", "assertion_2", "assertion_3"],
            "type": "object",
        }

    def test_json_schema__long_assertion__key_stays_short_description_has_full_text(
        self,
    ):
        long_assertion = (
            "The response must thoroughly address all aspects of the user's "
            "multi-part question, including historical context, current state, "
            "and future projections, without introducing any fabricated details"
        )
        schema = llm_judge_parsers.ResponseSchema([long_assertion])

        json_schema = schema.response_format.model_json_schema()

        prop = json_schema["properties"]["assertion_1"]
        assert prop["description"] == long_assertion
        assert len("assertion_1") < 64

    def test_parse__assertion_with_special_characters__handles_correctly(self):
        assertion = 'Response doesn\'t contain "quotes" or special chars: {}/\\'
        schema = llm_judge_parsers.ResponseSchema([assertion])
        content = json.dumps(
            {
                "assertion_1": {
                    "score": True,
                    "reason": "No special chars found",
                    "confidence": 0.88,
                }
            }
        )

        results = schema.parse(content)

        assert len(results) == 1
        assert results[0].name == assertion
        assert results[0].value is True
        assert results[0].category_name == "suite_assertion"
        assert results[0].metadata == {"confidence": 0.88}
