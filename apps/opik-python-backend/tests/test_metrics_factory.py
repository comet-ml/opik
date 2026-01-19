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


class TestCodeMetricSecurity:
    """Tests for code metric security validation.
    
    These tests verify that:
    1. Dangerous patterns are blocked
    2. Only allowed modules can be imported
    3. The feature can be disabled via environment variable
    4. Logging/audit trail is generated
    """

    def test_code_metric_basic_function_works(self):
        """Test that a basic safe function metric works."""
        code = '''
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        
        result = metric_fn({}, "test output")
        assert result.value == 1.0
        assert result.name == "test"

    def test_code_metric_basic_class_works(self):
        """Test that a basic safe class metric works."""
        code = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MyMetric(BaseMetric):
    def score(self, output, **kwargs):
        return ScoreResult(name="test", value=0.5, reason="Class metric")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        
        result = metric_fn({}, "test output")
        assert result.value == 0.5

    def test_code_metric_blocks_os_system(self):
        """Test that os.system is blocked."""
        code = '''
import os
os.system("echo hello")
def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)
        assert "OS system" in str(exc_info.value) or "not allowed" in str(exc_info.value)

    def test_code_metric_blocks_subprocess(self):
        """Test that subprocess is blocked."""
        code = '''
import subprocess
def my_metric(dataset_item, llm_output):
    subprocess.run(["ls"])
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)
        assert "subprocess" in str(exc_info.value)

    def test_code_metric_blocks_eval(self):
        """Test that eval() is blocked."""
        code = '''
def my_metric(dataset_item, llm_output):
    result = eval("1+1")
    return ScoreResult(name="test", value=float(result), reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)
        assert "eval" in str(exc_info.value)

    def test_code_metric_blocks_exec(self):
        """Test that exec() is blocked."""
        code = '''
def my_metric(dataset_item, llm_output):
    exec("x = 1")
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)

    def test_code_metric_blocks_open(self):
        """Test that open() for file operations is blocked."""
        code = '''
def my_metric(dataset_item, llm_output):
    with open("/etc/passwd", "r") as f:
        content = f.read()
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)
        assert "open" in str(exc_info.value)

    def test_code_metric_blocks_dunder_builtins(self):
        """Test that __builtins__ access is blocked."""
        code = '''
def my_metric(dataset_item, llm_output):
    x = __builtins__
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)

    def test_code_metric_blocks_socket(self):
        """Test that socket module is blocked."""
        code = '''
import socket
def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value) or "not allowed" in str(exc_info.value)

    def test_code_metric_blocks_requests(self):
        """Test that requests module is blocked."""
        code = '''
import requests
def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value) or "not allowed" in str(exc_info.value)

    def test_code_metric_blocks_dynamic_import(self):
        """Test that __import__ is blocked."""
        code = '''
def my_metric(dataset_item, llm_output):
    os = __import__("os")
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Security violation" in str(exc_info.value)

    def test_code_metric_allows_json(self):
        """Test that json module is allowed."""
        code = '''
import json
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    data = json.loads(llm_output) if llm_output.startswith("{") else {}
    return ScoreResult(name="test", value=1.0, reason="Used json")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, '{"key": "value"}')
        assert result.value == 1.0

    def test_code_metric_allows_re(self):
        """Test that re module is allowed."""
        code = '''
import re
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    match = re.search(r"\\d+", llm_output)
    return ScoreResult(name="test", value=1.0 if match else 0.0, reason="Used re")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test 123")
        assert result.value == 1.0

    def test_code_metric_allows_math(self):
        """Test that math module is allowed."""
        code = '''
import math
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=math.sqrt(0.25), reason="Used math")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test")
        assert result.value == 0.5

    def test_code_metric_allows_opik_imports(self):
        """Test that opik.* imports are allowed."""
        code = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="Opik imports work")
'''
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test")
        assert result.value == 1.0

    def test_code_metric_blocks_disallowed_modules(self):
        """Test that non-allowlisted modules are blocked."""
        code = '''
import pickle
def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "not allowed" in str(exc_info.value)
        assert "pickle" in str(exc_info.value)

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
def my_metric(dataset_item, llm_output)
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Invalid Python code" in str(exc_info.value)

    def test_code_metric_no_metric_function_raises_error(self):
        """Test that code without a valid metric function raises error."""
        code = '''
# Just a comment, no function
x = 1
'''
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")
        
        assert "Code must define" in str(exc_info.value)

    @patch.dict('os.environ', {'OPIK_CODE_METRIC_ENABLED': 'false'})
    def test_code_metric_disabled_via_env_var(self):
        """Test that code metrics can be disabled via environment variable."""
        # Need to reload the module to pick up the new env var
        import importlib
        from opik_backend.studio import metrics
        importlib.reload(metrics)
        
        code = '''
def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="OK")
'''
        try:
            with pytest.raises(InvalidMetricError) as exc_info:
                metrics.MetricFactory.build("code", {"code": code}, "model")
            
            assert "disabled" in str(exc_info.value).lower()
        finally:
            # Restore the module
            importlib.reload(metrics)


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


