"""Tests for MetricFactory in Optimization Studio."""

import pytest
from unittest.mock import MagicMock, patch

from opik.evaluation.metrics.score_result import ScoreResult
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


class TestCodeMetric:
    """Tests for code metric functionality.
    
    Code metrics use the same executor infrastructure as automations (evaluation metrics),
    executed via ProcessExecutor or DockerExecutor based on PYTHON_CODE_EXECUTOR_STRATEGY.
    
    Only BaseMetric class pattern is supported (same as automations).
    """

    def test_code_metric_basic_class_works(self):
        """Test that a basic class metric works."""
        code = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MyMetric(BaseMetric):
    def __init__(self, name: str = "test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=0.5, reason="Class metric")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        
        result = metric_fn({}, "test output")
        assert result.value == 0.5
        assert result.name == "test"

    def test_code_metric_uses_json(self):
        """Test that json module can be used."""
        code = '''
import json
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class JsonMetric(BaseMetric):
    def __init__(self, name: str = "json_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        data = json.loads(output) if output.startswith("{") else {}
        return ScoreResult(name=self.name, value=1.0, reason="Used json")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, '{"key": "value"}')
        assert result.value == 1.0

    def test_code_metric_uses_re(self):
        """Test that re module can be used."""
        code = '''
import re
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class RegexMetric(BaseMetric):
    def __init__(self, name: str = "regex_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        match = re.search(r"\\d+", output)
        return ScoreResult(name=self.name, value=1.0 if match else 0.0, reason="Used re")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test 123")
        assert result.value == 1.0

    def test_code_metric_uses_math(self):
        """Test that math module can be used."""
        code = '''
import math
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MathMetric(BaseMetric):
    def __init__(self, name: str = "math_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=math.sqrt(0.25), reason="Used math")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test")
        assert result.value == 0.5

    def test_code_metric_receives_dataset_fields_as_kwargs(self):
        """Test that dataset_item fields are passed as kwargs to score method."""
        code = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class KwargsMetric(BaseMetric):
    def __init__(self, name: str = "kwargs_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        expected = kwargs.get("expected_value", "")
        score = 1.0 if output == expected else 0.0
        return ScoreResult(name=self.name, value=score, reason=f"Expected: {expected}")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        
        # Test with matching expected_value
        result = metric_fn({"expected_value": "correct"}, "correct")
        assert result.value == 1.0
        
        # Test with non-matching expected_value
        result = metric_fn({"expected_value": "correct"}, "wrong")
        assert result.value == 0.0

    def test_code_metric_preserves_custom_name(self):
        """Test that the metric name defined by user is preserved."""
        code = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class CustomNamedMetric(BaseMetric):
    def __init__(self, name: str = "my_custom_metric_name"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=1.0, reason="Test")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test output")
        
        assert result.name == "my_custom_metric_name"

    def test_code_metric_missing_code_raises_error(self):
        """Test that missing code parameter raises error."""
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {}, "model")
        
        assert "Missing 'code' parameter" in str(exc_info.value)

    def test_code_metric_empty_code_raises_error(self):
        """Test that empty code raises error."""
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": ""}, "model")
        
        assert "Missing 'code' parameter" in str(exc_info.value)

    def test_code_metric_invalid_syntax_raises_error(self):
        """Test that invalid Python syntax raises error."""
        code = '''
class MyMetric(BaseMetric)
    def score(self, output, **kwargs):
        return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Invalid Python code" in str(exc_info.value)

    def test_code_metric_no_basemetric_class_raises_error(self):
        """Test that code without a BaseMetric subclass raises error at build time.
        
        With executor infrastructure, code must define a BaseMetric subclass.
        Validation at build time provides fail-fast behavior.
        """
        code = '''
# Just a comment, no BaseMetric class
x = 1
'''
        # Should raise InvalidMetricError during build (validation step)
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "BaseMetric" in str(exc_info.value)

    def test_code_metric_function_only_raises_error(self):
        """Test that function-only code (no BaseMetric class) raises error at build time.
        
        Function-based metrics are not supported - only BaseMetric class pattern.
        Validation at build time provides fail-fast behavior.
        """
        code = '''
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="Function")
'''
        # Should raise InvalidMetricError during build (validation step)
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "BaseMetric" in str(exc_info.value)


class TestGEvalTemplateInterpolation:
    """Tests for GEval metric template interpolation with dataset item fields."""

    def test_geval_with_none_params_uses_defaults(self):
        """Test that GEval handles explicit None params without crashing."""
        # Callers may explicitly pass None for optional fields
        params = {
            "task_introduction": None,
            "evaluation_criteria": None
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)

    def test_geval_with_missing_params_uses_defaults(self):
        """Test that GEval handles missing params using defaults."""
        params = {}
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)

    def test_geval_with_placeholders_handles_none_dataset_item(self):
        """Test that GEval with placeholders handles None dataset_item without crashing."""
        from opik_backend.studio.metrics import _interpolate_template
        
        params = {
            "task_introduction": "Evaluate the {{topic}} response",
            "evaluation_criteria": "Check if output matches {{answer}}"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)
        
        # Verify interpolation with None returns template unchanged (placeholders preserved)
        result = _interpolate_template(params["evaluation_criteria"], {})
        assert result == "Check if output matches {{answer}}"

    def test_geval_without_placeholders_creates_single_instance(self):
        """Test that GEval without placeholders creates a single reusable instance."""
        from opik_backend.studio.metrics import _interpolate_template
        
        params = {
            "task_introduction": "Evaluate the response quality",
            "evaluation_criteria": "Is the response helpful and accurate?"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)
        
        # Verify no interpolation changes static text
        dataset_item = {"answer": "42"}
        assert _interpolate_template(params["task_introduction"], dataset_item) == "Evaluate the response quality"
        assert _interpolate_template(params["evaluation_criteria"], dataset_item) == "Is the response helpful and accurate?"

    def test_geval_with_placeholders_in_criteria(self):
        """Test that GEval with {{field}} placeholders in criteria works."""
        from opik_backend.studio.metrics import _interpolate_template
        
        params = {
            "task_introduction": "Evaluate the response",
            "evaluation_criteria": "Check if the output matches the expected answer: {{answer}}"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)
        
        # Verify placeholder interpolation in criteria
        dataset_item = {"answer": "42"}
        result = _interpolate_template(params["evaluation_criteria"], dataset_item)
        assert result == "Check if the output matches the expected answer: 42"

    def test_geval_with_placeholders_in_task_introduction(self):
        """Test that GEval with {{field}} placeholders in task_introduction works."""
        from opik_backend.studio.metrics import _interpolate_template
        
        params = {
            "task_introduction": "You are evaluating a {{topic}} question",
            "evaluation_criteria": "Is the response accurate?"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)
        
        # Verify placeholder interpolation in task_introduction
        dataset_item = {"topic": "math"}
        result = _interpolate_template(params["task_introduction"], dataset_item)
        assert result == "You are evaluating a math question"

    def test_geval_with_multiple_placeholders(self):
        """Test that GEval with multiple {{field}} placeholders works."""
        from opik_backend.studio.metrics import _interpolate_template
        
        params = {
            "task_introduction": "Evaluate the {{task_type}} response for {{domain}}",
            "evaluation_criteria": "Expected answer is {{answer}}. Context: {{context}}"
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")
        
        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)
        
        # Verify multiple placeholder interpolation
        dataset_item = {
            "task_type": "homework",
            "domain": "algebra", 
            "answer": "x=5",
            "context": "solving equations"
        }
        intro_result = _interpolate_template(params["task_introduction"], dataset_item)
        criteria_result = _interpolate_template(params["evaluation_criteria"], dataset_item)
        
        assert intro_result == "Evaluate the homework response for algebra"
        assert criteria_result == "Expected answer is x=5. Context: solving equations"


class TestGEvalInterpolationHelpers:
    """Tests for GEval template interpolation helper functions."""

    def test_interpolate_template_single_field(self):
        """Test interpolating a single field."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Expected: {{answer}}"
        dataset_item = {"answer": "42"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Expected: 42"

    def test_interpolate_template_multiple_fields(self):
        """Test interpolating multiple fields."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Question: {{question}}, Expected: {{answer}}"
        dataset_item = {"question": "What is 6*7?", "answer": "42"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Question: What is 6*7?, Expected: 42"

    def test_interpolate_template_missing_field_unchanged(self):
        """Test that missing fields leave placeholder unchanged."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Expected: {{missing_field}}"
        dataset_item = {"answer": "42"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Expected: {{missing_field}}"

    def test_interpolate_template_no_placeholders(self):
        """Test template without placeholders returns unchanged."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "No placeholders here"
        dataset_item = {"answer": "42"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "No placeholders here"

    def test_interpolate_template_non_string_values(self):
        """Test that non-string values are converted to strings."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Count: {{count}}, Active: {{active}}"
        dataset_item = {"count": 123, "active": True}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Count: 123, Active: True"

    def test_interpolate_template_dotted_keys(self):
        """Test interpolating keys with dots (e.g., user.name)."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "User: {{user.name}}, ID: {{user.id}}"
        dataset_item = {"user.name": "Alice", "user.id": "12345"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "User: Alice, ID: 12345"

    def test_interpolate_template_hyphenated_keys(self):
        """Test interpolating keys with hyphens (e.g., answer-key)."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Answer: {{answer-key}}, Type: {{response-type}}"
        dataset_item = {"answer-key": "correct", "response-type": "multiple-choice"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Answer: correct, Type: multiple-choice"

    def test_interpolate_template_mixed_special_chars(self):
        """Test interpolating keys with mixed dots, hyphens, and underscores."""
        from opik_backend.studio.metrics import _interpolate_template
        
        template = "Value: {{var_with-special.chars}}"
        dataset_item = {"var_with-special.chars": "complex_value"}
        result = _interpolate_template(template, dataset_item)
        
        assert result == "Value: complex_value"

    def test_has_template_placeholders_true(self):
        """Test detecting placeholders in text."""
        from opik_backend.studio.metrics import _has_template_placeholders
        
        assert _has_template_placeholders("Contains {{field}}") is True
        assert _has_template_placeholders("Multiple {{a}} and {{b}}") is True

    def test_has_template_placeholders_false(self):
        """Test detecting no placeholders in text."""
        from opik_backend.studio.metrics import _has_template_placeholders
        
        assert _has_template_placeholders("No placeholders") is False
        assert _has_template_placeholders("Single braces {field}") is False
        assert _has_template_placeholders("") is False


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


