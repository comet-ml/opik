import pytest
from unittest.mock import Mock, patch

from opik import exceptions
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import TrajectoryAccuracy
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model


class TestTrajectoryAccuracy:
    """Test suite for TrajectoryAccuracy metric."""

    @pytest.fixture
    def mock_model(self):
        """Create a mock model for testing."""
        mock = Mock(spec=base_model.OpikBaseModel)
        assistant_response = {
            "role": "assistant",
            "content": '{"score": 0.8, "explanation": "Good trajectory execution"}',
        }
        mock.generate_chat_completion.return_value = assistant_response
        mock.agenerate_chat_completion.return_value = assistant_response
        return mock

    @pytest.fixture
    def trajectory_metric(self, mock_model):
        """Create a TrajectoryAccuracy metric with mocked model."""
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

        mock_model.generate_chat_completion.assert_called_once()
        call_args = mock_model.generate_chat_completion.call_args

        messages = call_args[1]["messages"]
        assert messages[0]["role"] == "system"
        assert messages[1]["role"] == "user"
        user_content = messages[1]["content"]
        assert goal in user_content
        assert "search_weather" in user_content
        assert final_result in user_content
        assert "Step 1:" in user_content

        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 0.8
        assert result.reason == "Good trajectory execution"
        assert result.name == trajectory_metric.name

    def test_score_empty_trajectory(self, trajectory_metric):
        """Test scoring with empty trajectory."""
        result = trajectory_metric.score(
            goal="Find something", trajectory=[], final_result="Found nothing"
        )

        assert isinstance(result, score_result.ScoreResult)

    def test_score_model_error_handling(self, trajectory_metric, mock_model):
        """Test error handling when model fails."""
        mock_model.generate_chat_completion.side_effect = Exception("Model failed")

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

    def test_score_passes_multistep_trajectory_to_model(
        self, trajectory_metric, mock_model
    ):
        """Multi-step trajectories appear verbatim in the user prompt sent to the model."""
        trajectory_metric.score(
            goal="Test goal",
            trajectory=[
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
            final_result="Test result",
        )

        messages = mock_model.generate_chat_completion.call_args[1]["messages"]
        user_content = messages[1]["content"]
        assert "Step 1:" in user_content
        assert "Step 2:" in user_content
        assert "First thought" in user_content
        assert "first_action()" in user_content
        assert "Second observation" in user_content
        assert "Test goal" in user_content
        assert "Test result" in user_content

    def test_score_passes_empty_trajectory_to_model(
        self, trajectory_metric, mock_model
    ):
        """Empty trajectories surface a placeholder note in the user prompt."""
        trajectory_metric.score(
            goal="Test goal", trajectory=[], final_result="Test result"
        )

        messages = mock_model.generate_chat_completion.call_args[1]["messages"]
        assert "No trajectory steps provided" in messages[1]["content"]

    @patch("opik.evaluation.models.models_factory.get")
    def test_init_model_string_model_name(self, mock_factory):
        """Test model initialization with string model name."""
        mock_model = Mock()
        mock_factory.return_value = mock_model

        metric = TrajectoryAccuracy(model="gpt-4", track=False)

        mock_factory.assert_called_once_with(model_name="gpt-4", track=False)
        assert metric is not None
        mock_model.generate_chat_completion.return_value = {
            "role": "assistant",
            "content": '{"score": 0.5, "explanation": "test"}',
        }
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

        assert isinstance(result, score_result.ScoreResult)
