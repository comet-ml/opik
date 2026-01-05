"""Tests for MetricFactory in Optimization Studio."""

import pytest
from unittest.mock import MagicMock, patch

from opik_backend.studio.metrics import MetricFactory
from opik_backend.studio.exceptions import InvalidMetricError
from opik_backend.studio.types import _convert_template_syntax, OptimizationConfig


class TestMetricFactory:
    """Tests for MetricFactory.build() and metric builders."""

    def test_build_unknown_metric_raises_error(self):
        """Test that building an unknown metric type raises InvalidMetricError."""
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("unknown_metric", {}, "openai/gpt-4o")
        
        assert "unknown_metric" in str(exc_info.value)
        assert "Available metrics:" in str(exc_info.value)

    def test_build_equals_metric(self):
        """Test building an equals metric."""
        metric_fn = MetricFactory.build("equals", {}, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "equals"
        assert callable(metric_fn)

    def test_build_equals_metric_with_params(self):
        """Test building an equals metric with custom parameters."""
        params = {
            "case_sensitive": False,
            "reference_key": "expected_output"
        }
        metric_fn = MetricFactory.build("equals", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "equals"
        assert callable(metric_fn)

    def test_build_levenshtein_metric(self):
        """Test building a levenshtein_ratio metric."""
        metric_fn = MetricFactory.build("levenshtein_ratio", {}, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "levenshtein_ratio"
        assert callable(metric_fn)

    def test_build_geval_metric(self):
        """Test building a geval metric."""
        params = {
            "task_introduction": "Evaluate the response quality",
            "evaluation_criteria": "Is the response helpful?"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)

    def test_build_json_schema_validator_metric(self):
        """Test building a json_schema_validator metric."""
        # The metric reads schema from dataset items via schema_key parameter
        metric_fn = MetricFactory.build("json_schema_validator", {}, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "json_schema_validator"
        assert callable(metric_fn)

    def test_build_json_schema_validator_metric_with_custom_schema_key(self):
        """Test building a json_schema_validator metric with custom schema_key."""
        params = {"schema_key": "my_schema"}
        metric_fn = MetricFactory.build("json_schema_validator", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "json_schema_validator"
        assert callable(metric_fn)

    def test_json_schema_validator_missing_schema_returns_zero(self):
        """Test that json_schema_validator returns 0.0 when schema is missing from dataset item."""
        metric_fn = MetricFactory.build("json_schema_validator", {}, "openai/gpt-4o")
        
        # Dataset item without json_schema key
        dataset_item = {"other_field": "value"}
        result = metric_fn(dataset_item, '{"name": "test"}')
        
        assert result.value == 0.0
        assert "Missing schema" in result.reason


class TestEqualsMetricExecution:
    """Tests for equals metric function execution."""

    def test_equals_metric_exact_match(self):
        """Test equals metric with exact match."""
        metric_fn = MetricFactory.build("equals", {"case_sensitive": True}, "model")
        
        # Default reference key is "answer"
        dataset_item = {"answer": "hello world"}
        result = metric_fn(dataset_item, "hello world")
        
        assert result.value == 1.0

    def test_equals_metric_no_match(self):
        """Test equals metric with no match."""
        metric_fn = MetricFactory.build("equals", {"case_sensitive": True}, "model")
        
        dataset_item = {"answer": "hello world"}
        result = metric_fn(dataset_item, "goodbye world")
        
        assert result.value == 0.0

    def test_equals_metric_case_insensitive(self):
        """Test equals metric with case insensitive comparison."""
        metric_fn = MetricFactory.build("equals", {"case_sensitive": False}, "model")
        
        dataset_item = {"answer": "Hello World"}
        result = metric_fn(dataset_item, "hello world")
        
        assert result.value == 1.0

    def test_equals_metric_custom_reference_key(self):
        """Test equals metric with custom reference key."""
        metric_fn = MetricFactory.build(
            "equals", 
            {"reference_key": "expected"}, 
            "model"
        )
        
        dataset_item = {"expected": "test value"}
        result = metric_fn(dataset_item, "test value")
        
        assert result.value == 1.0


class TestLevenshteinMetricExecution:
    """Tests for levenshtein_ratio metric function execution."""

    def test_levenshtein_metric_exact_match(self):
        """Test levenshtein metric with exact match."""
        metric_fn = MetricFactory.build("levenshtein_ratio", {}, "model")
        
        # Default reference key is "answer"
        dataset_item = {"answer": "hello"}
        result = metric_fn(dataset_item, "hello")
        
        assert result.value == 1.0

    def test_levenshtein_metric_partial_match(self):
        """Test levenshtein metric with partial match."""
        metric_fn = MetricFactory.build("levenshtein_ratio", {}, "model")
        
        dataset_item = {"answer": "hello"}
        result = metric_fn(dataset_item, "hallo")
        
        # "hello" vs "hallo" - 1 character difference out of 5
        assert 0.0 < result.value < 1.0

    def test_levenshtein_metric_no_match(self):
        """Test levenshtein metric with completely different strings."""
        metric_fn = MetricFactory.build("levenshtein_ratio", {}, "model")
        
        dataset_item = {"answer": "abc"}
        result = metric_fn(dataset_item, "xyz")
        
        assert result.value == 0.0


class TestMetricReasons:
    """Tests for metric reason fields (required for hierarchical_reflective optimizer)."""

    def test_equals_metric_includes_reason_on_match(self):
        """Test equals metric includes reason field on match."""
        metric_fn = MetricFactory.build("equals", {}, "model")
        dataset_item = {"answer": "test"}
        result = metric_fn(dataset_item, "test")
        
        assert result.reason is not None
        assert "match" in result.reason.lower()

    def test_equals_metric_includes_reason_on_no_match(self):
        """Test equals metric includes reason field on no match."""
        metric_fn = MetricFactory.build("equals", {}, "model")
        dataset_item = {"answer": "test"}
        result = metric_fn(dataset_item, "different")
        
        assert result.reason is not None
        assert "no match" in result.reason.lower()

    def test_levenshtein_metric_includes_reason(self):
        """Test levenshtein metric includes reason field with similarity percentage."""
        metric_fn = MetricFactory.build("levenshtein_ratio", {}, "model")
        dataset_item = {"answer": "hello"}
        result = metric_fn(dataset_item, "hallo")
        
        assert result.reason is not None
        assert "similarity" in result.reason.lower()
        assert "%" in result.reason


class TestTemplateSyntaxConversion:
    """Tests for template syntax conversion from {{var}} to {var}."""

    def test_convert_single_variable(self):
        """Test converting single variable."""
        result = _convert_template_syntax("Hello {{name}}")
        assert result == "Hello {name}"

    def test_convert_multiple_variables(self):
        """Test converting multiple variables."""
        result = _convert_template_syntax("{{greeting}} {{name}}!")
        assert result == "{greeting} {name}!"

    def test_preserve_single_braces(self):
        """Test that single braces are preserved."""
        result = _convert_template_syntax("Already {converted}")
        assert result == "Already {converted}"

    def test_no_variables(self):
        """Test string without variables."""
        result = _convert_template_syntax("No variables here")
        assert result == "No variables here"

    def test_empty_string(self):
        """Test empty string."""
        result = _convert_template_syntax("")
        assert result == ""

    def test_variable_in_sentence(self):
        """Test variable embedded in sentence."""
        result = _convert_template_syntax("What is the mime type for {{url}}?")
        assert result == "What is the mime type for {url}?"

    def test_optimization_config_converts_templates(self):
        """Test OptimizationConfig.from_dict converts template syntax in prompt messages."""
        config = {
            "dataset_name": "test_dataset",
            "prompt": {
                "messages": [
                    {"role": "system", "content": "Be helpful"},
                    {"role": "user", "content": "What is {{question}}? Answer: {{answer}}"}
                ]
            },
            "llm_model": {"model": "gpt-4o-mini", "parameters": {}},
            "evaluation": {"metrics": [{"type": "equals", "parameters": {}}]},
            "optimizer": {"type": "gepa", "parameters": {}}
        }
        
        opt_config = OptimizationConfig.from_dict(config)
        
        # System message should be unchanged (no variables)
        assert opt_config.prompt_messages[0]["content"] == "Be helpful"
        # User message should have converted variables
        assert opt_config.prompt_messages[1]["content"] == "What is {question}? Answer: {answer}"


