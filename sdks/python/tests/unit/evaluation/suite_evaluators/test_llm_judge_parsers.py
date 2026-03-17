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


class TestResponseSchema:
    def test_response_format__single_assertion__creates_model_with_one_field(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])

        assert "Response_is_accurate" in schema.response_format.model_fields

    def test_response_format__multiple_assertions__creates_model_with_all_fields(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful", "No hallucinations"]
        )

        assert "Response_is_accurate" in schema.response_format.model_fields
        assert "Response_is_helpful" in schema.response_format.model_fields
        assert "No_hallucinations" in schema.response_format.model_fields
        assert len(schema.response_format.model_fields) == 3

    def test_response_format__validates_valid_input(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])

        instance = schema.response_format(
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

    def test_response_format__rejects_missing_field(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful"]
        )

        with pytest.raises(Exception):
            schema.response_format(
                **{
                    "Response_is_accurate": {
                        "score": True,
                        "reason": "Correct",
                        "confidence": 0.9,
                    }
                }
            )

    def test_response_format__schema_has_valid_identifiers(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "No hallucinated info!"]
        )
        json_schema = schema.response_format.model_json_schema()

        for prop_name in json_schema["properties"]:
            assert prop_name.isidentifier(), (
                f"Property name '{prop_name}' is not a valid identifier"
            )

    def test_format_assertions__includes_keys_and_text(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "No hallucinations"]
        )

        formatted = schema.format_assertions()

        assert "- `Response_is_accurate`: Response is accurate" in formatted
        assert "- `No_hallucinations`: No hallucinations" in formatted

    def test_parse__valid_json__returns_score_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = json.dumps(
            {
                "Response_is_accurate": {
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

    def test_parse__invalid_json__returns_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = "not valid json"

        results = schema.parse(content)

        assert len(results) == 1
        assert results[0].name == "Response is accurate"
        assert results[0].value == 0.0
        assert results[0].scoring_failed is True
        assert results[0].category_name == "suite_assertion"
        assert "Failed to parse model output" in results[0].reason
        assert results[0].metadata["raw_output"] == content

    def test_parse__missing_assertion__returns_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(
            ["Response is accurate", "Response is helpful"]
        )
        content = json.dumps(
            {
                "Response_is_accurate": {
                    "score": True,
                    "reason": "Correct",
                    "confidence": 0.9,
                }
            }
        )

        results = schema.parse(content)

        assert len(results) == 2
        assert all(r.scoring_failed is True for r in results)
        assert all(r.value == 0.0 for r in results)
        assert all(r.category_name == "suite_assertion" for r in results)

    def test_parse__missing_required_field__returns_failed_results(self):
        schema = llm_judge_parsers.ResponseSchema(["Response is accurate"])
        content = json.dumps(
            {
                "Response_is_accurate": {
                    "score": True,
                }
            }
        )

        results = schema.parse(content)

        assert len(results) == 1
        assert results[0].scoring_failed is True
        assert results[0].value == 0.0
        assert results[0].category_name == "suite_assertion"

    def test_parse__empty_assertions__returns_empty_list(self):
        schema = llm_judge_parsers.ResponseSchema([])

        results = schema.parse("{}")

        assert len(results) == 0

    def test_parse__assertion_with_special_characters__handles_correctly(self):
        assertion = 'Response doesn\'t contain "quotes" or special chars: {}/\\'
        schema = llm_judge_parsers.ResponseSchema([assertion])
        sanitized_key = list(schema.response_format.model_fields.keys())[0]
        content = json.dumps(
            {
                sanitized_key: {
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
