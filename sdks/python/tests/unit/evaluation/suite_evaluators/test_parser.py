import pytest
from opik import exceptions
from opik.evaluation.suite_evaluators import parser


class TestParseModelOutput:
    def test_parse_model_output__valid_json__returns_score_results(self):
        content = """
        {
            "results": [
                {"name": "factual", "value": 1, "reason": "Correct answer", "metadata": {"pass_score": 0.95}},
                {"name": "helpful", "value": 0, "reason": "Could be more detailed", "metadata": {"pass_score": 0.3}}
            ]
        }
        """
        assertions = [
            {"name": "factual", "description": "The answer is factually correct"},
            {"name": "helpful", "description": "The answer is helpful"},
        ]
        results = parser.parse_model_output(
            content=content, name="test", assertions=assertions
        )

        assert len(results) == 2

        assert results[0].name == "test_factual"
        assert results[0].value == 1.0
        assert results[0].reason == "Correct answer"
        assert results[0].metadata == {
            "pass_score": 0.95,
            "assertion_text": "The answer is factually correct",
        }
        assert results[0].scoring_failed is False

        assert results[1].name == "test_helpful"
        assert results[1].value == 0.0
        assert results[1].reason == "Could be more detailed"
        assert results[1].metadata == {
            "pass_score": 0.3,
            "assertion_text": "The answer is helpful",
        }
        assert results[1].scoring_failed is False

    def test_parse_model_output__json_in_markdown_block__extracts_correctly(self):
        content = """
        Here is the evaluation:
        ```json
        {
            "results": [
                {"name": "accurate", "value": 1, "reason": "All facts correct", "metadata": {"pass_score": 1.0}}
            ]
        }
        ```
        """
        assertions = [{"name": "accurate", "description": "All facts are accurate"}]
        results = parser.parse_model_output(
            content=content, name="judge", assertions=assertions
        )

        assert len(results) == 1
        assert results[0].name == "judge_accurate"
        assert results[0].value == 1.0
        assert results[0].metadata["assertion_text"] == "All facts are accurate"

    def test_parse_model_output__invalid_json__raises_error(self):
        content = "This is not valid JSON"
        assertions = [{"name": "test", "description": "Test"}]

        with pytest.raises(exceptions.MetricComputationError, match="Failed to parse"):
            parser.parse_model_output(content=content, name="test", assertions=assertions)

    def test_parse_model_output__missing_results_array__raises_error(self):
        content = '{"score": 1}'
        assertions = [{"name": "test", "description": "Test"}]

        with pytest.raises(
            exceptions.MetricComputationError, match="missing 'results' array"
        ):
            parser.parse_model_output(content=content, name="test", assertions=assertions)

    def test_parse_model_output__empty_results_array__returns_empty_list(self):
        content = '{"results": []}'
        assertions = []
        results = parser.parse_model_output(
            content=content, name="test", assertions=assertions
        )

        assert len(results) == 0

    def test_parse_model_output__missing_optional_fields__uses_defaults(self):
        content = """
        {
            "results": [
                {"name": "test_assertion", "value": 1}
            ]
        }
        """
        assertions = [{"name": "test_assertion", "description": "Test assertion desc"}]
        results = parser.parse_model_output(
            content=content, name="metric", assertions=assertions
        )

        assert len(results) == 1
        assert results[0].name == "metric_test_assertion"
        assert results[0].value == 1.0
        assert results[0].reason == ""
        assert results[0].metadata == {
            "pass_score": 1.0,
            "assertion_text": "Test assertion desc",
        }

    def test_parse_model_output__empty_name__uses_assertion_name_only(self):
        content = """
        {
            "results": [
                {"name": "standalone", "value": 1, "reason": "OK", "metadata": {"pass_score": 0.9}}
            ]
        }
        """
        assertions = [{"name": "standalone", "description": "Standalone assertion"}]
        results = parser.parse_model_output(content=content, name="", assertions=assertions)

        assert len(results) == 1
        assert results[0].name == "standalone"
        assert results[0].metadata["assertion_text"] == "Standalone assertion"

    def test_parse_model_output__unknown_assertion__empty_assertion_text(self):
        content = """
        {
            "results": [
                {"name": "unknown_assertion", "value": 1, "reason": "OK", "metadata": {"pass_score": 0.8}}
            ]
        }
        """
        assertions = [{"name": "different_name", "description": "Different assertion"}]
        results = parser.parse_model_output(
            content=content, name="test", assertions=assertions
        )

        assert len(results) == 1
        assert results[0].name == "test_unknown_assertion"
        assert results[0].metadata["assertion_text"] == ""
