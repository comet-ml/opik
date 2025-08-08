import pytest
from unittest.mock import Mock, patch

from opik.evaluation.metrics.llm_judges.structure_output_compliance import template
from opik.evaluation.metrics.llm_judges.structure_output_compliance.metric import (
    StructuredOutputCompliance,
)
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model
from opik import exceptions


class TestStructuredOutputComplianceTemplate:
    """Test suite for StructuredOutputCompliance template functions."""

    def test_generate_query_basic(self):
        """Test basic query generation without schema or examples."""
        output = '{"name": "John", "age": 30}'

        query = template.generate_query(output=output)

        assert output in query
        assert "You are an expert in structured data validation" in query
        assert "EXPECTED STRUCTURE" in query
        assert "OUTPUT:" in query
        assert "Respond in the following JSON format:" in query
        assert "(No schema provided — assume valid JSON)" in query
        assert "EXAMPLES:" not in query

    def test_generate_query_with_schema(self):
        """Test query generation with schema."""
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"

        query = template.generate_query(output=output, schema=schema)

        assert output in query
        assert schema in query
        assert "(No schema provided — assume valid JSON)" not in query

    def test_generate_query_with_few_shot_examples(self):
        """Test query generation with few-shot examples."""
        output = '{"name": "John", "age": 30}'
        few_shot_examples = [
            {
                "title": "Valid JSON",
                "output": '{"name": "Alice", "age": 25}',
                "schema": "User(name: str, age: int)",
                "score": True,
                "reason": "Valid JSON format",
            },
            {
                "title": "Invalid JSON",
                "output": '{"name": "Bob", age: 30}',
                "schema": "User(name: str, age: int)",
                "score": False,
                "reason": "Missing quotes around age value",
            },
        ]

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert "EXAMPLES:" in query
        assert "Valid JSON" in query
        assert "Invalid JSON" in query
        assert "Alice" in query
        assert "Bob" in query
        assert "true" in query
        assert "false" in query
        assert "Valid JSON format" in query
        assert "Missing quotes around age value" in query
        assert "<example>" in query
        assert "</example>" in query

    def test_generate_query_with_schema_and_examples(self):
        """Test query generation with both schema and few-shot examples."""
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"
        few_shot_examples = [
            {
                "title": "Valid Example",
                "output": '{"name": "Alice", "age": 25}',
                "schema": "User(name: str, age: int)",
                "score": True,
                "reason": "Valid format",
            }
        ]

        query = template.generate_query(
            output=output, schema=schema, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert schema in query
        assert "EXAMPLES:" in query
        assert "Valid Example" in query

    def test_generate_query_empty_examples_list(self):
        """Test query generation with empty examples list."""
        output = '{"name": "John", "age": 30}'
        few_shot_examples = []

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert "EXAMPLES:" not in query

    def test_generate_query_example_without_schema(self):
        """Test query generation with examples that don't have schema."""
        output = '{"name": "John", "age": 30}'
        few_shot_examples = [
            {
                "title": "Valid JSON",
                "output": '{"name": "Alice"}',
                "score": True,
                "reason": "Valid JSON format",
            }
        ]

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert "Expected Schema: None" in query
        assert "Valid JSON" in query
        assert "true" in query

    def test_generate_query_template_structure(self):
        """Test that the generated query has the correct template structure."""
        output = '{"test": "data"}'

        query = template.generate_query(output=output)

        assert "You are an expert in structured data validation" in query
        assert "Guidelines:" in query
        assert "1. OUTPUT must be a valid JSON object" in query
        assert "2. If a schema is provided" in query
        assert "3. If no schema is provided" in query
        assert "4. Common formatting issues" in query
        assert "5. Partial compliance is considered non-compliant" in query
        assert "6. Respond only in the specified JSON format" in query
        assert "EXPECTED STRUCTURE (optional):" in query
        assert "OUTPUT:" in query
        assert '"score": true or false' in query
        assert '"reason": ["list of reasons' in query


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
            {
                "title": "Valid Example",
                "output": '{"name": "Alice", "age": 25}',
                "schema": "User(name: str, age: int)",
                "score": True,
                "reason": "Valid format",
            }
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
