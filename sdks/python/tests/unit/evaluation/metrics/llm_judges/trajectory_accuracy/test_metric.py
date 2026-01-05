import pytest
from unittest.mock import Mock, patch

from opik import exceptions
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import TrajectoryAccuracy
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import templates
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model


class TestTrajectoryAccuracy:
    """Test suite for TrajectoryAccuracy metric."""

    @pytest.fixture
    def mock_model(self):
        """Create a mock model for testing."""
        mock = Mock(spec=base_model.OpikBaseModel)
        mock.generate_string.return_value = (
            '{"score": 0.8, "explanation": "Good trajectory execution"}'
        )
        mock.agenerate_string.return_value = (
            '{"score": 0.8, "explanation": "Good trajectory execution"}'
        )
        return mock

    @pytest.fixture
    def trajectory_metric(self, mock_model):
        """Create a TrajectoryAccuracy metric with mocked model."""
        # Pass the mock model directly to the constructor as intended
        # This avoids mocking protected members and uses the public API properly
        metric = TrajectoryAccuracy(model=mock_model, track=False)
        return metric

    def test_score_basic_trajectory(self, trajectory_metric, mock_model):
        """Test basic trajectory accuracy scoring."""
        goal = "Find the weather in Paris"
        trajectory = [
            {
                "thought": "I need to search for weather information",
                "action": "search_weather(location='Paris')",
                "observation": "Weather: 22°C, sunny",
            }
        ]
        final_result = "The weather in Paris is 22°C and sunny"

        result = trajectory_metric.score(
            goal=goal, trajectory=trajectory, final_result=final_result
        )

        # Verify model was called
        mock_model.generate_string.assert_called_once()
        call_args = mock_model.generate_string.call_args

        # Check the prompt contains key elements
        prompt = call_args[1]["input"]
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
            goal="Find something", trajectory=[], final_result="Found nothing"
        )

        # Should still work, just with empty trajectory
        assert isinstance(result, score_result.ScoreResult)

    def test_score_model_error_handling(self, trajectory_metric, mock_model):
        """Test error handling when model fails."""
        mock_model.generate_string.side_effect = Exception("Model failed")

        # Should raise MetricComputationError when model fails
        with pytest.raises(
            exceptions.MetricComputationError,
            match="Trajectory accuracy evaluation failed: Model failed",
        ):
            trajectory_metric.score(
                goal="Test goal",
                trajectory=[
                    {"thought": "test", "action": "test", "observation": "test"}
                ],
                final_result="Test result",
            )

    def test_format_trajectory_steps_valid_trajectory(self):
        """Test trajectory formatting using public templates API."""
        example = {
            "goal": "Test goal",
            "trajectory": [
                {
                    "thought": "First thought",
                    "action": "first_action()",
                    "observation": "First observation",
                },
                {
                    "thought": "Second thought",
                    "action": "second_action()",
                    "observation": "Second observation",
                },
            ],
            "final_result": "Test result",
        }

        formatted_prompt = templates.create_evaluation_prompt(example)

        assert "Step 1:" in formatted_prompt
        assert "Step 2:" in formatted_prompt
        assert "First thought" in formatted_prompt
        assert "first_action()" in formatted_prompt
        assert "Second observation" in formatted_prompt
        assert "Test goal" in formatted_prompt
        assert "Test result" in formatted_prompt

    def test_format_trajectory_steps_empty_trajectory(self):
        """Test formatting empty trajectory using public templates API."""
        example = {"goal": "Test goal", "trajectory": [], "final_result": "Test result"}

        formatted_prompt = templates.create_evaluation_prompt(example)
        assert "No trajectory steps provided" in formatted_prompt

    @patch("opik.evaluation.models.models_factory.get")
    def test_init_model_string_model_name(self, mock_factory):
        """Test model initialization with string model name."""
        mock_model = Mock()
        mock_factory.return_value = mock_model

        metric = TrajectoryAccuracy(model="gpt-4", track=False)

        mock_factory.assert_called_once_with(model_name="gpt-4", track=False)
        # Test that the model was initialized correctly by testing behavior
        # rather than accessing private attributes
        assert metric is not None
        # We can test the public interface works correctly
        mock_model.generate_string.return_value = (
            '{"score": 0.5, "explanation": "test"}'
        )
        result = metric.score(goal="test", trajectory=[], final_result="test")
        assert isinstance(result, score_result.ScoreResult)

    def test_ignored_kwargs(self, trajectory_metric):
        """Test that extra kwargs are properly ignored."""
        result = trajectory_metric.score(
            goal="Test goal",
            trajectory=[{"thought": "test", "action": "test", "observation": "test"}],
            final_result="Test result",
            extra_param="should be ignored",
            another_param=123,
        )

        # Should work without issues
        assert isinstance(result, score_result.ScoreResult)
