import pytest
from unittest.mock import Mock, patch

from opik import exceptions
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import TrajectoryAccuracy
from opik.evaluation.metrics import score_result


class TestTrajectoryAccuracy:
    """Test suite for TrajectoryAccuracy metric."""

    @pytest.fixture
    def mock_model(self):
        """Create a mock model for testing."""
        mock = Mock()
        mock.generate_string.return_value = '{"score": 0.8, "explanation": "Good trajectory execution"}'
        mock.agenerate_string.return_value = '{"score": 0.8, "explanation": "Good trajectory execution"}'
        return mock

    @pytest.fixture
    def trajectory_metric(self, mock_model):
        """Create a TrajectoryAccuracy metric with mocked model."""
        metric = TrajectoryAccuracy(track=False)
        metric._model = mock_model
        return metric

    def test_score_basic_trajectory(self, trajectory_metric, mock_model):
        """Test basic trajectory accuracy scoring."""
        goal = "Find the weather in Paris"
        trajectory = [
            {
                "thought": "I need to search for weather information",
                "action": "search_weather(location='Paris')",
                "observation": "Weather: 22°C, sunny"
            }
        ]
        final_result = "The weather in Paris is 22°C and sunny"

        result = trajectory_metric.score(
            goal=goal,
            trajectory=trajectory,
            final_result=final_result
        )

        # Verify model was called
        mock_model.generate_string.assert_called_once()
        call_args = mock_model.generate_string.call_args

        # Check the prompt contains key elements
        prompt = call_args[1]['input']
        assert goal in prompt
        assert "search_weather" in prompt
        assert final_result in prompt
        assert "Step 1:" in prompt

        # Check result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 0.8
        assert result.reason == "Good trajectory execution"
        assert result.name == trajectory_metric.name

    def test_score_empty_trajectory(self, trajectory_metric):
        """Test scoring with empty trajectory."""
        result = trajectory_metric.score(
            goal="Find something",
            trajectory=[],
            final_result="Found nothing"
        )

        # Should still work, just with empty trajectory
        assert isinstance(result, score_result.ScoreResult)

    def test_score_model_error_handling(self, trajectory_metric, mock_model):
        """Test error handling when model fails."""
        mock_model.generate_string.side_effect = Exception("Model failed")

        result = trajectory_metric.score(
            goal="Test goal",
            trajectory=[{"thought": "test", "action": "test", "observation": "test"}],
            final_result="Test result"
        )

        # Should return error result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 0.0
        assert "Evaluation failed: Model failed" in result.reason

    def test_parse_evaluation_response_valid_response(self, trajectory_metric):
        """Test parsing valid evaluation response."""
        content = '{"score": 0.75, "explanation": "Decent trajectory with some issues"}'
        
        result = trajectory_metric._parse_evaluation_response(content)
        
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 0.75
        assert result.reason == "Decent trajectory with some issues"

    def test_parse_evaluation_response_score_out_of_range(self, trajectory_metric):
        """Test parsing response with score out of valid range."""
        content = '{"score": 1.5, "explanation": "Score too high"}'
        
        with pytest.raises(exceptions.MetricComputationError, match="Invalid response format"):
            trajectory_metric._parse_evaluation_response(content)

    def test_format_trajectory_steps_valid_trajectory(self, trajectory_metric):
        """Test trajectory formatting."""
        trajectory = [
            {
                "thought": "First thought",
                "action": "first_action()",
                "observation": "First observation"
            },
            {
                "thought": "Second thought",
                "action": "second_action()",
                "observation": "Second observation"
            }
        ]
        
        formatted = trajectory_metric._format_trajectory_steps(trajectory)
        
        assert "Step 1:" in formatted
        assert "Step 2:" in formatted
        assert "First thought" in formatted
        assert "first_action()" in formatted
        assert "Second observation" in formatted

    def test_format_trajectory_steps_empty_trajectory(self, trajectory_metric):
        """Test formatting empty trajectory."""
        formatted = trajectory_metric._format_trajectory_steps([])
        assert formatted == "No trajectory steps provided"

    @patch('opik.evaluation.models.models_factory.get')
    def test_init_model_string_model_name(self, mock_factory):
        """Test model initialization with string model name."""
        mock_model = Mock()
        mock_factory.return_value = mock_model
        
        metric = TrajectoryAccuracy(model="gpt-4", track=False)
        
        mock_factory.assert_called_once_with(model_name="gpt-4")
        assert metric._model == mock_model

    def test_ignored_kwargs(self, trajectory_metric):
        """Test that extra kwargs are properly ignored."""
        result = trajectory_metric.score(
            goal="Test goal",
            trajectory=[{"thought": "test", "action": "test", "observation": "test"}],
            final_result="Test result",
            extra_param="should be ignored",
            another_param=123
        )
        
        # Should work without issues
        assert isinstance(result, score_result.ScoreResult) 