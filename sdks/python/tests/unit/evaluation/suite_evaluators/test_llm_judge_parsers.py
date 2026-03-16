import json

import pytest

from opik.evaluation.suite_evaluators.llm_judge import parsers as llm_judge_parsers


class TestSanitizeFieldName:
    def test_simple_text__replaces_spaces_with_underscores(self):
        assert (
            llm_judge_parsers._sanitize_field_name("Response is accurate")
            == "Response_is_accurate"
        )

    def test_special_characters__replaced_and_collapsed(self):
        result = llm_judge_parsers._sanitize_field_name(
            'Response doesn\'t contain "quotes" or special chars: {}/\\'
        )
        assert result == "Response_doesn_t_contain_quotes_or_special_chars"

    def test_leading_digit__prepends_prefix(self):
        assert llm_judge_parsers._sanitize_field_name("123abc") == "a_123abc"

    def test_empty_string__returns_prefix(self):
        assert llm_judge_parsers._sanitize_field_name("!!!") == "a_"


class TestBuildFieldMapping:
    def test_unique_assertions__no_collisions(self):
        mapping = llm_judge_parsers._build_field_mapping(
            ["Response is accurate", "No hallucinations"]
        )
        assert list(mapping.values()) == ["Response is accurate", "No hallucinations"]
        assert list(mapping.keys()) == ["Response_is_accurate", "No_hallucinations"]

    def test_colliding_assertions__appends_suffix(self):
        mapping = llm_judge_parsers._build_field_mapping(["a!b", "a@b"])
        keys = list(mapping.keys())
        assert keys[0] == "a_b"
        assert keys[1] == "a_b_2"
        assert list(mapping.values()) == ["a!b", "a@b"]

    def test_underscore_vs_space__no_silent_collision(self):
        mapping = llm_judge_parsers._build_field_mapping(
            ["Response is accurate", "Response_is_accurate"]
        )
        keys = list(mapping.keys())
        assert keys[0] == "Response_is_accurate"
        assert keys[1] == "Response_is_accurate_2"
        assert list(mapping.values()) == [
            "Response is accurate",
            "Response_is_accurate",
        ]


class TestBuildResponseFormatModel:
    def test_build_response_format_model__single_assertion__creates_model_with_one_field(
        self,
    ):
        assertions = ["Response is accurate"]

        model, mapping = llm_judge_parsers.build_response_format_model(assertions)

        assert "Response_is_accurate" in model.model_fields
        assert mapping == {"Response_is_accurate": "Response is accurate"}

    def test_build_response_format_model__multiple_assertions__creates_model_with_all_fields(
        self,
    ):
        assertions = [
            "Response is accurate",
            "Response is helpful",
            "No hallucinations",
        ]

        model, mapping = llm_judge_parsers.build_response_format_model(assertions)

        assert "Response_is_accurate" in model.model_fields
        assert "Response_is_helpful" in model.model_fields
        assert "No_hallucinations" in model.model_fields
        assert len(model.model_fields) == 3

    def test_build_response_format_model__validates_valid_input(self):
        assertions = ["Response is accurate"]
        model, mapping = llm_judge_parsers.build_response_format_model(assertions)

        instance = model(
            **{
                "Response_is_accurate": {
                    "score": True,
                    "reason": "The response is correct",
                    "confidence": 0.95,
                }
            }
        )

        result = getattr(instance, "Response_is_accurate")
        assert result.score is True
        assert result.reason == "The response is correct"
        assert result.confidence == 0.95

    def test_build_response_format_model__rejects_missing_field(self):
        assertions = ["Response is accurate", "Response is helpful"]
        model, _ = llm_judge_parsers.build_response_format_model(assertions)

        with pytest.raises(Exception):
            model(
                **{
                    "Response_is_accurate": {
                        "score": True,
                        "reason": "Correct",
                        "confidence": 0.9,
                    }
                }
            )

    def test_build_response_format_model__schema_has_valid_identifiers(self):
        assertions = ["Response is accurate", "No hallucinated info!"]
        model, _ = llm_judge_parsers.build_response_format_model(assertions)
        schema = model.model_json_schema()

        for prop_name in schema["properties"]:
            assert prop_name.isidentifier(), (
                f"Property name '{prop_name}' is not a valid identifier"
            )


class TestParseModelOutput:
    def test_parse_model_output__valid_json__returns_score_results(self):
        assertions = ["Response is accurate"]
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = json.dumps(
            {
                "Response_is_accurate": {
                    "score": True,
                    "reason": "The response correctly states Paris",
                    "confidence": 0.95,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value is True
        assert results[0].reason == "The response correctly states Paris"
        assert results[0].scoring_failed is False
        assert results[0].category_name == "suite_assertion"
        assert results[0].metadata == {"confidence": 0.95}

    def test_parse_model_output__multiple_assertions__returns_results_in_order(self):
        assertions = ["First assertion", "Second assertion", "Third assertion"]
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = json.dumps(
            {
                "First_assertion": {
                    "score": True,
                    "reason": "First reason",
                    "confidence": 0.9,
                },
                "Second_assertion": {
                    "score": False,
                    "reason": "Second reason",
                    "confidence": 0.85,
                },
                "Third_assertion": {
                    "score": True,
                    "reason": "Third reason",
                    "confidence": 0.7,
                },
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 3
        assert results[0].name == "First assertion"
        assert results[0].value is True
        assert results[0].category_name == "suite_assertion"
        assert results[0].metadata == {"confidence": 0.9}
        assert results[1].name == "Second assertion"
        assert results[1].value is False
        assert results[1].category_name == "suite_assertion"
        assert results[1].metadata == {"confidence": 0.85}
        assert results[2].name == "Third assertion"
        assert results[2].value is True
        assert results[2].category_name == "suite_assertion"
        assert results[2].metadata == {"confidence": 0.7}

    def test_parse_model_output__invalid_json__returns_failed_results(self):
        assertions = ["Response is accurate"]
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = "not valid json"

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value == 0.0
        assert results[0].scoring_failed is True
        assert results[0].category_name == "suite_assertion"
        assert "Failed to parse model output" in results[0].reason
        assert results[0].metadata["raw_output"] == content

    def test_parse_model_output__missing_assertion__returns_failed_results(self):
        assertions = ["Response is accurate", "Response is helpful"]
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = json.dumps(
            {
                "Response_is_accurate": {
                    "score": True,
                    "reason": "Correct",
                    "confidence": 0.9,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 2
        assert all(r.scoring_failed is True for r in results)
        assert all(r.value == 0.0 for r in results)
        assert all(r.category_name == "suite_assertion" for r in results)

    def test_parse_model_output__missing_required_field__returns_failed_results(self):
        assertions = ["Response is accurate"]
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = json.dumps(
            {
                "Response_is_accurate": {
                    "score": True,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 1
        assert results[0].scoring_failed is True
        assert results[0].value == 0.0
        assert results[0].category_name == "suite_assertion"

    def test_parse_model_output__empty_assertions__returns_empty_list(self):
        assertions: list[str] = []
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        content = "{}"

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 0

    def test_parse_model_output__assertion_with_special_characters__handles_correctly(
        self,
    ):
        assertions = ['Response doesn\'t contain "quotes" or special chars: {}/\\']
        _, mapping = llm_judge_parsers.build_response_format_model(assertions)
        sanitized_key = list(mapping.keys())[0]
        content = json.dumps(
            {
                sanitized_key: {
                    "score": True,
                    "reason": "No special chars found",
                    "confidence": 0.88,
                }
            }
        )

        results = llm_judge_parsers.parse_model_output(content, assertions, mapping)

        assert len(results) == 1
        assert (
            results[0].name
            == 'Response doesn\'t contain "quotes" or special chars: {}/\\'
        )
        assert results[0].value is True
        assert results[0].category_name == "suite_assertion"
        assert results[0].metadata == {"confidence": 0.88}
