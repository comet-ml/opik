import json

import pytest

from opik.evaluation.suite_evaluators.llm_judge import parsers as llm_judge_parsers


class TestBuildResponseFormatModel:
    def test_build_response_format_model__single_assertion__creates_model_with_one_field(
        self,
    ):
        assertions = ["Response is accurate"]

        model = llm_judge_parsers.build_response_format_model(assertions)

        assert "Response is accurate" in model.model_fields

    def test_build_response_format_model__multiple_assertions__creates_model_with_all_fields(
        self,
    ):
        assertions = [
            "Response is accurate",
            "Response is helpful",
            "No hallucinations",
        ]

        model = llm_judge_parsers.build_response_format_model(assertions)

        assert "Response is accurate" in model.model_fields
        assert "Response is helpful" in model.model_fields
        assert "No hallucinations" in model.model_fields
        assert len(model.model_fields) == 3

    def test_build_response_format_model__validates_valid_input(self):
        assertions = ["Response is accurate"]
        model = llm_judge_parsers.build_response_format_model(assertions)

        instance = model(
            **{
                "Response is accurate": {
                    "score": True,
                    "reason": "The response is correct",
                    "confidence": 0.95,
                }
            }
        )

        result = getattr(instance, "Response is accurate")
        assert result.score is True
        assert result.reason == "The response is correct"
        assert result.confidence == 0.95

    def test_build_response_format_model__rejects_missing_field(self):
        assertions = ["Response is accurate", "Response is helpful"]
        model = llm_judge_parsers.build_response_format_model(assertions)

        with pytest.raises(Exception):
            model(
                **{
                    "Response is accurate": {
                        "score": True,
                        "reason": "Correct",
                        "confidence": 0.9,
                    }
                }
            )


class TestParseModelOutput:
    def test_parse_model_output__valid_json__returns_score_results(self):
        assertions = ["Response is accurate"]
        content = json.dumps(
            {
                "Response is accurate": {
                    "score": True,
                    "reason": "The response correctly states Paris",
                    "confidence": 0.95,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value is True
        assert results[0].reason == "The response correctly states Paris"
        assert results[0].scoring_failed is False
        assert results[0].metadata == {"confidence": 0.95}

    def test_parse_model_output__multiple_assertions__returns_results_in_order(self):
        assertions = ["First assertion", "Second assertion", "Third assertion"]
        content = json.dumps(
            {
                "First assertion": {
                    "score": True,
                    "reason": "First reason",
                    "confidence": 0.9,
                },
                "Second assertion": {
                    "score": False,
                    "reason": "Second reason",
                    "confidence": 0.85,
                },
                "Third assertion": {
                    "score": True,
                    "reason": "Third reason",
                    "confidence": 0.7,
                },
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions)

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

    def test_parse_model_output__invalid_json__returns_failed_results(self):
        assertions = ["Response is accurate"]
        content = "not valid json"

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value == 0.0
        assert results[0].scoring_failed is True
        assert "Failed to parse model output" in results[0].reason
        assert results[0].metadata["raw_output"] == content

    def test_parse_model_output__missing_assertion__returns_failed_results(self):
        assertions = ["Response is accurate", "Response is helpful"]
        content = json.dumps(
            {
                "Response is accurate": {
                    "score": True,
                    "reason": "Correct",
                    "confidence": 0.9,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 2
        assert all(r.scoring_failed is True for r in results)
        assert all(r.value == 0.0 for r in results)

    def test_parse_model_output__missing_required_field__returns_failed_results(self):
        assertions = ["Response is accurate"]
        content = json.dumps(
            {
                "Response is accurate": {
                    "score": True,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 1
        assert results[0].scoring_failed is True
        assert results[0].value == 0.0

    def test_parse_model_output__empty_assertions__returns_empty_list(self):
        assertions: list[str] = []
        content = "{}"

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 0

    def test_parse_model_output__assertion_with_special_characters__handles_correctly(
        self,
    ):
        assertions = ['Response doesn\'t contain "quotes" or special chars: {}/\\']
        content = json.dumps(
            {
                'Response doesn\'t contain "quotes" or special chars: {}/\\': {
                    "score": True,
                    "reason": "No special chars found",
                    "confidence": 0.88,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions)

        assert len(results) == 1
        assert (
            results[0].name
            == 'Response doesn\'t contain "quotes" or special chars: {}/\\'
        )
        assert results[0].value is True
        assert results[0].metadata == {"confidence": 0.88}
