import pytest
from unittest.mock import Mock, patch

from opik.evaluation.metrics.llm_judges.structure_output_compliance.metric import (
    StructuredOutputCompliance,
)
from opik.evaluation.metrics.llm_judges.structure_output_compliance.schema import (
    FewShotExampleStructuredOutputCompliance,
)
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model
from opik import exceptions


class TestStructuredOutputComplianceMetric:
    """Test suite for StructuredOutputCompliance metric class."""

    @pytest.fixture
    def mock_model(self):
        """Create a mock model for testing."""
        mock = Mock(spec=base_model.OpikBaseModel)
        mock.generate_string.return_value = (
            '{"score": true, "reason": ["Valid JSON format", "Correct structure"]}'
        )
        mock.agenerate_string.return_value = (
            '{"score": true, "reason": ["Valid JSON format", "Correct structure"]}'
        )
        return mock

    @pytest.fixture
    def structured_output_metric(self, mock_model):
        """Create a StructuredOutputCompliance metric with mocked model."""
        metric = StructuredOutputCompliance(model=mock_model, track=False)
        return metric

    def test_score_basic_compliance(self, structured_output_metric, mock_model):
        """Test basic structured output compliance scoring."""
        output = '{"name": "John", "age": 30}'

        result = structured_output_metric.score(output=output)

        # Verify model was called
        mock_model.generate_string.assert_called_once()
        call_args = mock_model.generate_string.call_args

        # Check the prompt contains key elements
        prompt = call_args[1]["input"]
        assert output in prompt
        assert "You are an expert in structured data validation" in prompt
        assert "OUTPUT:" in prompt

        # Check result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 1.0
        assert result.reason == "Valid JSON format\nCorrect structure"
        assert result.name == structured_output_metric.name

    def test_score_with_schema(self, structured_output_metric, mock_model):
        """Test structured output compliance scoring with schema."""
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"

        result = structured_output_metric.score(output=output, schema=schema)

        # Verify model was called
        mock_model.generate_string.assert_called_once()
        call_args = mock_model.generate_string.call_args

        # Check the prompt contains schema
        prompt = call_args[1]["input"]
        assert schema in prompt

        # Check result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 1.0

    def test_score_with_few_shot_examples(self, mock_model):
        """Test structured output compliance scoring with few-shot examples."""
        few_shot_examples = [
            FewShotExampleStructuredOutputCompliance(
                title="Valid Example",
                output='{"name": "Alice", "age": 25}',
                output_schema="User(name: str, age: int)",
                score=True,
                reason="Valid format",
            )
        ]

        metric = StructuredOutputCompliance(
            model=mock_model, few_shot_examples=few_shot_examples, track=False
        )

        result = metric.score(output='{"name": "John", "age": 30}')

        # Verify model was called
        mock_model.generate_string.assert_called_once()
        call_args = mock_model.generate_string.call_args

        # Check the prompt contains examples
        prompt = call_args[1]["input"]
        assert "EXAMPLES:" in prompt
        assert "Valid Example" in prompt

        # Check result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 1.0

    def test_score_model_error_handling(self, structured_output_metric, mock_model):
        """Test error handling when model fails."""
        mock_model.generate_string.side_effect = Exception("Model failed")

        # Should raise MetricComputationError when model fails
        with pytest.raises(
            exceptions.MetricComputationError,
            match="Structured output compliance evaluation failed: Model failed",
        ):
            structured_output_metric.score(output='{"test": "data"}')

    def test_score_ignored_kwargs(self, structured_output_metric):
        """Test that extra kwargs are properly ignored."""
        result = structured_output_metric.score(
            output='{"test": "data"}',
            extra_param="should be ignored",
            another_param=123,
        )

        # Should work without issues
        assert isinstance(result, score_result.ScoreResult)

    @pytest.mark.asyncio
    async def test_ascore_async_compliance(self, structured_output_metric, mock_model):
        """Test async structured output compliance scoring."""
        output = '{"name": "John", "age": 30}'

        result = await structured_output_metric.ascore(output=output)

        # Verify model was called
        mock_model.agenerate_string.assert_called_once()
        call_args = mock_model.agenerate_string.call_args

        # Check the prompt contains key elements
        prompt = call_args[1]["input"]
        assert output in prompt
        assert "You are an expert in structured data validation" in prompt

        # Check result
        assert isinstance(result, score_result.ScoreResult)
        assert result.value == 1.0
        assert result.reason == "Valid JSON format\nCorrect structure"

    @patch("opik.evaluation.models.models_factory.get")
    def test_init_model_string_model_name(self, mock_factory):
        """Test model initialization with string model name."""
        mock_model = Mock()
        mock_factory.return_value = mock_model

        metric = StructuredOutputCompliance(model="gpt-4", track=False)

        mock_factory.assert_called_once_with(model_name="gpt-4")
        # Test that the model was initialized correctly by testing behavior
        assert metric is not None
        # We can test the public interface works correctly
        mock_model.generate_string.return_value = '{"score": true, "reason": ["test"]}'
        result = metric.score(output='{"test": "data"}')
        assert isinstance(result, score_result.ScoreResult)
