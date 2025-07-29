import pytest

from opik import exceptions
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import TrajectoryAccuracy
from opik.evaluation.metrics.llm_judges import parsing_helpers


class TestTrajectoryAccuracyParser:
    """Test suite for TrajectoryAccuracy parsing functionality."""

    def test_trajectory_accuracy_score_out_of_range_high(self):
        """Test trajectory accuracy score validation with score > 1.0."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = (
            '{"score": 1.2, "explanation": "Score exceeds valid range."}'
        )

        # The parsing helper itself doesn't validate range - that's done in the metric
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert parsed_content["score"] == 1.2
        assert parsed_content["explanation"] == "Score exceeds valid range."

    def test_trajectory_accuracy_score_out_of_range_low(self):
        """Test trajectory accuracy score validation with score < 0.0."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = (
            '{"score": -0.1, "explanation": "Score below valid range."}'
        )

        # The parsing helper itself doesn't validate range - that's done in the metric
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert parsed_content["score"] == -0.1
        assert parsed_content["explanation"] == "Score below valid range."

    def test_trajectory_accuracy_missing_score_field(self):
        """Test trajectory accuracy parsing with missing score field."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = '{"explanation": "Missing score field."}'

        # This should parse successfully but missing fields will be caught in metric validation
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert "score" not in parsed_content
        assert parsed_content["explanation"] == "Missing score field."

    def test_trajectory_accuracy_missing_explanation_field(self):
        """Test trajectory accuracy parsing with missing explanation field."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = '{"score": 0.8}'

        # This should parse successfully but missing fields will be caught in metric validation
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert parsed_content["score"] == 0.8
        assert "explanation" not in parsed_content

    def test_trajectory_accuracy_invalid_json_format(self):
        """Test trajectory accuracy parsing with invalid JSON format."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = "This is not valid JSON at all"

        with pytest.raises(exceptions.JSONParsingError):
            parsing_helpers.extract_json_content_or_raise(invalid_model_output)

    def test_trajectory_accuracy_valid_json_parsing(self):
        """Test trajectory accuracy parsing with valid JSON."""
        metric = TrajectoryAccuracy(track=False)
        valid_model_output = '{"score": 0.85, "explanation": "Good trajectory with logical reasoning and appropriate actions."}'

        # This should parse successfully
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            valid_model_output
        )

        assert parsed_content["score"] == 0.85
        assert (
            parsed_content["explanation"]
            == "Good trajectory with logical reasoning and appropriate actions."
        )

    def test_trajectory_accuracy_json_with_extra_fields(self):
        """Test trajectory accuracy parsing with extra fields in JSON."""
        metric = TrajectoryAccuracy(track=False)
        valid_model_output = '{"score": 0.7, "explanation": "Decent trajectory", "extra_field": "should be ignored"}'

        # Should parse successfully and ignore extra fields
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            valid_model_output
        )

        assert parsed_content["score"] == 0.7
        assert parsed_content["explanation"] == "Decent trajectory"
        assert "extra_field" in parsed_content  # Extra fields are preserved

    @pytest.mark.parametrize("score_value", [0.0, 0.25, 0.5, 0.75, 1.0])
    def test_trajectory_accuracy_valid_score_range(self, score_value):
        """Test trajectory accuracy parsing with various valid scores."""
        metric = TrajectoryAccuracy(track=False)
        valid_model_output = f'{{"score": {score_value}, "explanation": "Test explanation for score {score_value}"}}'

        parsed_content = parsing_helpers.extract_json_content_or_raise(
            valid_model_output
        )

        assert parsed_content["score"] == score_value
        assert f"score {score_value}" in parsed_content["explanation"]

    def test_trajectory_accuracy_empty_explanation(self):
        """Test trajectory accuracy parsing with empty explanation."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = '{"score": 0.8, "explanation": ""}'

        # Empty explanation should be caught in validation
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert parsed_content["explanation"] == ""
        # The metric's _parse_evaluation_response would catch this as invalid

    def test_trajectory_accuracy_non_numeric_score(self):
        """Test trajectory accuracy parsing with non-numeric score."""
        metric = TrajectoryAccuracy(track=False)
        invalid_model_output = '{"score": "high", "explanation": "Non-numeric score"}'

        # This should parse but fail validation in the metric
        parsed_content = parsing_helpers.extract_json_content_or_raise(
            invalid_model_output
        )
        assert parsed_content["score"] == "high"
        # The metric's _parse_evaluation_response would catch this when converting to float
