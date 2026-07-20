"""Tests for MetricFactory in Optimization Studio."""

import pytest
from unittest.mock import MagicMock, patch

from opik.evaluation.metrics.score_result import ScoreResult
from opik_backend.studio.metrics import MetricFactory
from opik_backend.studio.exceptions import InvalidMetricError, InvalidConfigError
from opik_backend.process_worker import validate_user_code
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
        params = {"case_sensitive": False, "reference_key": "expected_output"}
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
            "evaluation_criteria": "Is the response helpful?",
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
        metric_fn = MetricFactory.build(
            "json_schema_validator", params, "openai/gpt-4o"
        )

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
            "equals", {"reference_key": "expected"}, "model"
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
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MyMetric(BaseMetric):
    def __init__(self, name: str = "test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=0.5, reason="Class metric")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")

        result = metric_fn({}, "test output")
        assert result.value == 0.5
        assert result.name == "test"

    def test_code_metric_uses_json(self):
        """Test that json module can be used."""
        code = """
import json
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class JsonMetric(BaseMetric):
    def __init__(self, name: str = "json_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        data = json.loads(output) if output.startswith("{") else {}
        return ScoreResult(name=self.name, value=1.0, reason="Used json")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, '{"key": "value"}')
        assert result.value == 1.0

    def test_code_metric_uses_re(self):
        """Test that re module can be used."""
        code = """
import re
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class RegexMetric(BaseMetric):
    def __init__(self, name: str = "regex_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        match = re.search(r"\\d+", output)
        return ScoreResult(name=self.name, value=1.0 if match else 0.0, reason="Used re")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test 123")
        assert result.value == 1.0

    def test_code_metric_uses_math(self):
        """Test that math module can be used."""
        code = """
import math
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MathMetric(BaseMetric):
    def __init__(self, name: str = "math_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=math.sqrt(0.25), reason="Used math")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test")
        assert result.value == 0.5

    def test_code_metric_receives_dataset_fields_as_kwargs(self):
        """Test that dataset_item fields are passed as kwargs to score method."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class KwargsMetric(BaseMetric):
    def __init__(self, name: str = "kwargs_test"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        expected = kwargs.get("expected_value", "")
        score = 1.0 if output == expected else 0.0
        return ScoreResult(name=self.name, value=score, reason=f"Expected: {expected}")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")

        # Test with matching expected_value
        result = metric_fn({"expected_value": "correct"}, "correct")
        assert result.value == 1.0

        # Test with non-matching expected_value
        result = metric_fn({"expected_value": "correct"}, "wrong")
        assert result.value == 0.0

    def test_code_metric_preserves_custom_name(self):
        """Test that the metric name defined by user is preserved."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class CustomNamedMetric(BaseMetric):
    def __init__(self, name: str = "my_custom_metric_name"):
        super().__init__(name=name)
    
    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=1.0, reason="Test")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({}, "test output")

        assert result.name == "my_custom_metric_name"

    def test_code_metric_name_from_super_init_literal(self):
        """Name recovered from ``super().__init__(name="...")`` when there is no
        ``name`` __init__ param (the most common idiom). Regression guard: the
        static AST extractor must not silently fall back to "code" here."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class SuperInitMetric(BaseMetric):
    def __init__(self):
        super().__init__(name="my_super_metric")

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=1.0, reason="Test")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        assert metric_fn.__name__ == "my_super_metric"
        result = metric_fn({}, "test output")
        assert result.name == "my_super_metric"

    def test_code_metric_aliased_basemetric_import_builds(self):
        """A class subclassing an aliased ``BaseMetric`` import
        (``import BaseMetric as BM``) must still be detected at build time."""
        code = """
from opik.evaluation.metrics import BaseMetric as BM
from opik.evaluation.metrics.score_result import ScoreResult

class AliasedMetric(BM):
    def __init__(self):
        super().__init__(name="aliased_metric")

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=1.0, reason="Test")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        assert metric_fn.__name__ == "aliased_metric"
        result = metric_fn({}, "test output")
        assert result.value == 1.0

    def test_code_metric_name_ignores_helper_init_call(self):
        """A `Helper.__init__(name=...)` inside the metric's __init__ must NOT be
        mistaken for the metric name — only the base constructor's name counts."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class Helper:
    def __init__(self, name="helper_name"):
        self.tag = name

class RealMetric(BaseMetric):
    def __init__(self):
        # Explicit non-base __init__ call declared BEFORE super() — the old
        # `ast.walk` scan would have returned "helper_name" from here.
        Helper.__init__(self, name="helper_name")
        super().__init__(name="real_metric")

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=1.0, reason="Test")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        assert metric_fn.__name__ == "real_metric"

    def test_code_metric_two_classes_uses_alphabetically_first(self):
        """When a file declares multiple metric classes, the statically-detected
        signature must match the class runtime `get_metric_class` instantiates —
        the alphabetically-first by name (inspect.getmembers order). Here the
        strict-signature `AlphaMetric` (declared second, alphabetically first)
        must win over the `**kwargs` `ZetaMetric` (declared first)."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class ZetaMetric(BaseMetric):
    def __init__(self):
        super().__init__(name="zeta")

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=0.1, reason="zeta")

class AlphaMetric(BaseMetric):
    def __init__(self):
        super().__init__(name="alpha")

    def score(self, output, reference):
        return ScoreResult(name=self.name, value=1.0, reason="alpha")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        # Name is read from AlphaMetric (alphabetically first), matching runtime.
        assert metric_fn.__name__ == "alpha"
        # AlphaMetric's strict signature needs `reference`; supply it and confirm
        # it is what actually runs (value 1.0, not zeta's 0.1).
        result = metric_fn({"reference": "x"}, "test output")
        assert result.value == 1.0

    def test_code_metric_indirect_subclass_selection_matches_runtime(self):
        """An INDIRECT (transitive) BaseMetric subclass defined in the file must
        be selected consistently at build and run time.

        `AMetric(ZBase)` subclasses BaseMetric only via `ZBase`; it is
        alphabetically first, so runtime get_metric_class instantiates it. The
        build-time selector must resolve the same class transitively (not fall
        back to the direct-subclass ZBase), otherwise the wrong signature/name is
        applied and every item silently scores 0.0 / the name never matches.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class ZBase(BaseMetric):
    def __init__(self, name="zbase"):
        super().__init__(name=name)

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=0.1, reason="z")

class AMetric(ZBase):
    def __init__(self):
        super().__init__(name="ametric")

    def score(self, output, reference):
        return ScoreResult(name=self.name, value=1.0, reason="a")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        # AMetric (indirect, alphabetically first) — not the direct base ZBase.
        assert metric_fn.__name__ == "ametric"
        # AMetric's strict signature runs (1.0), not ZBase's **kwargs 0.1.
        result = metric_fn({"reference": "x"}, "out")
        assert result.value == 1.0

    def test_code_metric_ignores_imported_basemetric_subclass(self):
        """An IMPORTED concrete BaseMetric subclass (e.g. Equals) must not be
        instantiated instead of the user's defined metric, even when its name
        sorts first. Runtime selection is restricted to classes defined in the
        file, matching the build-time AST selector.
        """
        code = """
from opik.evaluation.metrics import BaseMetric, Equals
from opik.evaluation.metrics.score_result import ScoreResult

class MyMetric(BaseMetric):
    def __init__(self):
        super().__init__(name="mymetric")

    def score(self, output, **kwargs):
        return ScoreResult(name=self.name, value=0.42, reason="mine")
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        # "Equals" sorts before "MyMetric" but is imported, so MyMetric wins.
        assert metric_fn.__name__ == "mymetric"
        result = metric_fn({}, "out")
        assert result.value == 0.42
        assert result.name == "mymetric"

    def test_validate_user_code_imported_base_defers_instead_of_rejecting(self):
        """A class whose only BaseMetric link is an IMPORTED base can't be
        resolved statically, but runtime instantiates it fine — so build defers
        with permissive defaults rather than hard-rejecting (OPIK-7172 regression
        guard)."""
        code = """
from some_external_pkg import CustomBase

class MyMetric(CustomBase):
    def score(self, output, **kwargs):
        return None
"""
        result = validate_user_code(code)
        assert "code" not in result  # not a 400
        assert result["name"] is None
        assert result["accepts_var_keyword"] is True
        assert result["score_params"] == []

    def test_validate_user_code_no_class_is_rejected_at_build(self):
        """A file with no class at all is a definite non-metric -> 400 at build."""
        result = validate_user_code("x = 1\n")
        assert result.get("code") == 400
        assert "BaseMetric" in result["error"]

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
        code = """
class MyMetric(BaseMetric)
    def score(self, output, **kwargs):
        return ScoreResult(name="test", value=1.0, reason="OK")
"""
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")

        assert "invalid Python code" in str(exc_info.value)

    def test_code_metric_no_basemetric_class_raises_error(self):
        """Test that code without a BaseMetric subclass raises error at build time.

        With executor infrastructure, code must define a BaseMetric subclass.
        Validation at build time provides fail-fast behavior.
        """
        code = """
# Just a comment, no BaseMetric class
x = 1
"""
        # Should raise InvalidMetricError during build (validation step)
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")

        assert "BaseMetric" in str(exc_info.value)

    def test_code_metric_function_only_raises_error(self):
        """Test that function-only code (no BaseMetric class) raises error at build time.

        Function-based metrics are not supported - only BaseMetric class pattern.
        Validation at build time provides fail-fast behavior.
        """
        code = """
from opik.evaluation.metrics.score_result import ScoreResult

def my_metric(dataset_item, llm_output):
    return ScoreResult(name="test", value=1.0, reason="Function")
"""
        # Should raise InvalidMetricError during build (validation step)
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build("code", {"code": code}, "model")

        assert "BaseMetric" in str(exc_info.value)

    def test_code_metric_required_kwarg_key_now_builds(self):
        """A metric reading a required dataset key must build without score().

        Regression guard for OPIK-7172: the old build-time probe ran
        score(output="") with no dataset columns, so a metric doing
        kwargs["x"] threw KeyError and was killed at build though it scores
        fine on real data. Build now only validates + instantiates (no
        score()), so the metric builds and scores correctly against real data.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class StrictKwargMetric(BaseMetric):
    def __init__(self, name: str = "strict_kwarg"):
        super().__init__(name=name)

    def score(self, output, **kwargs):
        # Required key: absent under the old dummy-payload probe -> KeyError.
        expected = kwargs["expected_value"]
        return ScoreResult(
            name=self.name,
            value=1.0 if output == expected else 0.0,
            reason=f"Expected: {expected}",
        )
"""
        # Building must NOT raise (no score() call at build time).
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        assert metric_fn.__name__ == "strict_kwarg"

        # And it scores correctly once real data is available.
        result = metric_fn({"expected_value": "hi"}, "hi")
        assert result.value == 1.0
        assert result.name == "strict_kwarg"

    def test_code_metric_runtime_score_error_returns_zero(self):
        """A metric that raises inside score() builds, then scores 0.0 at run time.

        This preserves the asymmetry the fix intends: build-time is now purely
        static (validate + instantiate), while a genuine score() failure on real
        data is still swallowed to ScoreResult(0.0) with an explanatory reason.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class BoomMetric(BaseMetric):
    def __init__(self, name: str = "boom"):
        super().__init__(name=name)

    def score(self, output, **kwargs):
        raise ValueError("kaboom")
"""
        # Builds fine: no score() at build time.
        metric_fn = MetricFactory.build("code", {"code": code}, "model")

        # Runtime score() error is caught and reported as 0.0 with an
        # explanatory (traceback-derived, truncated) reason.
        result = metric_fn({}, "anything")
        assert result.value == 0.0
        assert "Error" in result.reason

    def test_code_metric_arguments_map_renames_column_strict_signature(self):
        """A STRICT signature + rename map + EXTRA columns must still score.

        Regression guard for OPIK-7172: ``score(self, output, reference)`` has no
        ``**kwargs``, so any un-consumed dataset column (here ``category`` and
        ``id``) splatted into the call would land as an unexpected keyword ->
        TypeError -> swallowed to ScoreResult(0.0). The build-time
        ``accepts_var_keyword`` detection must restrict ``data`` to ``output`` +
        the mapped ``reference`` only, so the metric scores its real, non-trivial
        result (matching -> 1.0, mismatch -> 0.0) instead of a masked 0.0.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class RenameMetric(BaseMetric):
    def __init__(self, name: str = "rename"):
        super().__init__(name=name)

    def score(self, output, reference):
        return ScoreResult(
            name=self.name,
            value=1.0 if output == reference else 0.0,
            reason=f"reference={reference}",
        )
"""
        metric_fn = MetricFactory.build(
            "code",
            {"code": code, "arguments": {"reference": "expected_answer"}},
            "model",
        )

        # Dataset item carries EXTRA columns not in the arguments map. A strict
        # signature would blow up on these if they were splatted.
        item = {"expected_answer": "Paris", "category": "geo", "id": "abc-123"}

        # A genuine match must score a NON-TRIVIAL 1.0 (not a masked 0.0 from a
        # swallowed TypeError), and the mapped column must arrive under 'reference'.
        match = metric_fn(item, "Paris")
        assert match.value == 1.0
        assert "Paris" in match.reason

        # A genuine mismatch scores 0.0 for the right reason.
        no_match = metric_fn(item, "London")
        assert no_match.value == 0.0
        assert "Paris" in no_match.reason

    def test_code_metric_strict_signature_without_output_param(self):
        """A STRICT metric whose score() does NOT declare `output` must score.

        Regression guard for OPIK-7172: `output` was unconditionally injected
        into the score() kwargs, so a strict signature that doesn't declare it
        (here `def score(self, reference)`) received an unexpected `output=`
        keyword -> TypeError -> swallowed to a masked ScoreResult(0.0). Injection
        is now gated on the signature actually accepting `output`, so the metric
        scores its real result from `reference` alone.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class NoOutputMetric(BaseMetric):
    def __init__(self, name: str = "no_output"):
        super().__init__(name=name)

    def score(self, reference):
        return ScoreResult(
            name=self.name,
            value=1.0 if reference == "Paris" else 0.0,
            reason=f"reference={reference}",
        )
"""
        metric_fn = MetricFactory.build(
            "code",
            {"code": code, "arguments": {"reference": "expected_answer"}},
            "model",
        )
        item = {"expected_answer": "Paris"}

        # Must score a real 1.0 (not a masked 0.0 from an unexpected `output=`).
        result = metric_fn(item, "any llm output")
        assert result.value == 1.0
        assert result.name == "no_output"
        assert "Paris" in result.reason

    def test_code_metric_arguments_map_keeps_kwargs_backcompat(self):
        """Unmapped columns are still splatted for **kwargs metrics.

        With an 'arguments' map present, a column NOT consumed as a rename
        source is still passed through, so a **kwargs metric keeps seeing it
        (back-compat). The renamed param also arrives under its new name.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MixedMetric(BaseMetric):
    def __init__(self, name: str = "mixed"):
        super().__init__(name=name)

    def score(self, output, reference, **kwargs):
        extra = kwargs.get("category", "")
        return ScoreResult(
            name=self.name,
            value=1.0 if (output == reference and extra == "geo") else 0.0,
            reason=f"reference={reference}, category={extra}",
        )
"""
        metric_fn = MetricFactory.build(
            "code",
            {"code": code, "arguments": {"reference": "expected_answer"}},
            "model",
        )

        result = metric_fn({"expected_answer": "Paris", "category": "geo"}, "Paris")
        assert result.value == 1.0

    def test_code_metric_no_arguments_map_full_splat(self):
        """No 'arguments' map preserves the historical full-splat behavior."""
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class SplatMetric(BaseMetric):
    def __init__(self, name: str = "splat"):
        super().__init__(name=name)

    def score(self, output, **kwargs):
        return ScoreResult(
            name=self.name,
            value=1.0 if kwargs.get("expected_answer") == output else 0.0,
            reason="splat",
        )
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        result = metric_fn({"expected_answer": "42"}, "42")
        assert result.value == 1.0

    def test_code_metric_strict_signature_no_arguments_extra_columns(self):
        """Strict score() + NO arguments map + extra columns must still score.

        Regression guard for OPIK-7172 (reviewer finding): without a map the old
        code full-splatted every column into a strict ``score(self, output,
        reference)`` -> the extra ``category``/``id`` land as unexpected keywords
        -> TypeError -> masked ScoreResult(0.0). Declared params must resolve by
        same-name column and extras must be dropped, so a real match scores 1.0.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class StrictNoMapMetric(BaseMetric):
    def __init__(self, name: str = "strict_no_map"):
        super().__init__(name=name)

    def score(self, output, reference):
        return ScoreResult(
            name=self.name,
            value=1.0 if output == reference else 0.0,
            reason=f"reference={reference}",
        )
"""
        metric_fn = MetricFactory.build("code", {"code": code}, "model")
        item = {"reference": "Paris", "category": "geo", "id": "x1"}
        assert metric_fn(item, "Paris").value == 1.0
        assert metric_fn(item, "London").value == 0.0

    def test_code_metric_non_string_arguments_values_dropped(self):
        """Non-string 'arguments' values are dropped, not passed to score().

        Reviewer finding: the backend's raw JsonNode can carry non-string values;
        they must not reach the dataset-key lookup (only column names are
        strings). A strict metric with a bogus numeric mapping still scores via
        the same-name column fallback rather than crashing.
        """
        code = """
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class ArgTypeMetric(BaseMetric):
    def __init__(self, name: str = "arg_type"):
        super().__init__(name=name)

    def score(self, output, reference):
        return ScoreResult(name=self.name, value=1.0 if output == reference else 0.0, reason="ok")
"""
        metric_fn = MetricFactory.build(
            "code",
            {"code": code, "arguments": {"reference": 123, "bogus": ["a"]}},
            "model",
        )
        # Non-string values dropped -> 'reference' resolves by same-name column.
        assert metric_fn({"reference": "Paris"}, "Paris").value == 1.0


class TestJsonPathReferenceKey:
    """Tests for JSONPath support in reference_key for equals and levenshtein metrics."""

    def test_equals_jsonpath_filter_expression(self):
        """Test equals metric with a JSONPath filter to extract a value from a JSON array."""
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "$.feedback_scores[?(@.name == 'Useful')].value"},
            "model",
        )

        dataset_item = {
            "feedback_scores": [
                {"name": "Usefulness", "value": 0.8, "source": "ONLINE_SCORING"},
                {"name": "Useful", "category_name": "Fail", "value": 0, "source": "UI"},
                {"name": "Useful-Numerical", "value": 2, "source": "UI"},
            ]
        }
        result = metric_fn(dataset_item, "0")
        assert result.value == 1.0

    def test_levenshtein_jsonpath_filter_expression(self):
        """Test levenshtein metric with a JSONPath filter expression."""
        metric_fn = MetricFactory.build(
            "levenshtein_ratio",
            {"reference_key": "$.feedback_scores[?(@.name == 'Useful')].category_name"},
            "model",
        )

        dataset_item = {
            "feedback_scores": [
                {"name": "Usefulness", "category_name": "Pass", "value": 0.8},
                {"name": "Useful", "category_name": "Fail", "value": 0},
            ]
        }
        result = metric_fn(dataset_item, "Fail")
        assert result.value == 1.0

    def test_jsonpath_index_access(self):
        """Test reference_key with a JSONPath array index."""
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "$.scores[0].value"},
            "model",
        )

        dataset_item = {
            "scores": [
                {"name": "first", "value": "42"},
                {"name": "second", "value": "99"},
            ]
        }
        result = metric_fn(dataset_item, "42")
        assert result.value == 1.0

    def test_jsonpath_no_match_scores_zero_with_reason(self):
        """A JSONPath that resolves nothing must not silently match empty output.

        Regression guard for OPIK-7160: the old behavior defaulted an
        unresolvable reference to "" and reported a perfect 1.0 against empty
        output, hiding the misconfiguration. It now scores 0.0 and explains why.
        """
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "$.feedback_scores[?(@.name == 'NonExistent')].value"},
            "model",
        )

        dataset_item = {
            "feedback_scores": [
                {"name": "Useful", "value": 0},
            ]
        }
        result = metric_fn(dataset_item, "")
        assert result.value == 0.0
        assert "Missing reference value" in result.reason

    def test_jsonpath_no_match_against_nonempty_output(self):
        """A JSONPath with no matches scores 0 against non-empty output."""
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "$.feedback_scores[?(@.name == 'NonExistent')].value"},
            "model",
        )

        dataset_item = {
            "feedback_scores": [
                {"name": "Useful", "value": 0},
            ]
        }
        result = metric_fn(dataset_item, "something")
        assert result.value == 0.0

    def test_plain_key_still_works(self):
        """Test that plain field names continue to work as before."""
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "answer"},
            "model",
        )

        dataset_item = {"answer": "hello"}
        result = metric_fn(dataset_item, "hello")
        assert result.value == 1.0

    def test_is_jsonpath_detection(self):
        """Test the heuristic that distinguishes plain keys from JSONPath expressions."""
        from opik_backend.studio.metrics import _is_jsonpath

        assert _is_jsonpath("answer") is False
        assert _is_jsonpath("expected_output") is False
        assert _is_jsonpath("my-field") is False
        assert _is_jsonpath("my.field") is False

        assert _is_jsonpath("$.answer") is True
        assert _is_jsonpath("scores[0].value") is True
        assert _is_jsonpath("$.scores[?(@.name == 'x')].value") is True
        assert _is_jsonpath("items..value") is True


class TestReferenceKeyValidation:
    """Build-time validation that a reference_key resolves against the dataset.

    Guards OPIK-7160: a reference_key matching no dataset field silently scored
    every item 0, so no candidate could beat the baseline and the optimizer
    returned the seed prompt while the run reported "completed". Building the
    metric now fails loudly instead, keeping that failure distinguishable from a
    legitimate "no improvement over baseline" run (OPIK-7038).
    """

    def test_equals_build_raises_when_key_resolves_no_item(self):
        dataset_items = [{"answer": "a"}, {"answer": "b"}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "equals",
                {"reference_key": "label"},  # not a dataset field
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        message = str(exc_info.value)
        assert "label" in message
        assert "did not resolve" in message
        # Available fields are surfaced to make the fix obvious.
        assert "answer" in message

    def test_levenshtein_build_raises_when_key_resolves_no_item(self):
        dataset_items = [{"answer": "a"}, {"answer": "b"}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "levenshtein_ratio",
                {"reference_key": "typo"},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "did not resolve" in str(exc_info.value)

    def test_numerical_similarity_build_raises_when_key_resolves_no_item(self):
        dataset_items = [{"score": 1}, {"score": 2}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "numerical_similarity",
                {"reference_key": "value"},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "did not resolve" in str(exc_info.value)

    def test_build_passes_when_key_resolves_for_some_items(self):
        # Sparse data: the key is present on only one item. Validation passes;
        # missing items are handled per-item at scoring time.
        dataset_items = [{"answer": "a"}, {"other": "b"}]
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "answer"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        assert callable(metric_fn)

    def test_build_skips_validation_without_provider(self):
        # No dataset available (e.g. config validation) -> do not guess, skip.
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "anything"},
            "model",
        )
        assert callable(metric_fn)

    def test_build_skips_validation_for_empty_dataset(self):
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "anything"},
            "model",
            dataset_items_provider=lambda: [],
        )
        assert callable(metric_fn)

    def test_equals_jsonpath_build_raises_when_no_item_matches(self):
        dataset_items = [
            {"feedback_scores": [{"name": "Useful", "value": 0}]},
        ]
        with pytest.raises(InvalidMetricError):
            MetricFactory.build(
                "equals",
                {"reference_key": "$.feedback_scores[?(@.name == 'Missing')].value"},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )

    def test_malformed_jsonpath_build_raises_with_syntax_error(self):
        # A JSONPath-shaped key with invalid syntax that matches no literal field
        # must fail with a JSONPath-specific message, not the generic
        # "did not resolve" one (which hides the real cause). The literal
        # fallback in _resolve_reference otherwise swallows the parse error.
        dataset_items = [{"answer": "42"}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "equals",
                {"reference_key": "$.foo["},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "not a valid JSONPath expression" in str(exc_info.value)

    def test_numerical_similarity_malformed_jsonpath_build_raises(self):
        # Same guard on the numerical_similarity validation path, which infers
        # scale from a separate resolution loop.
        dataset_items = [{"score": 3}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "numerical_similarity",
                {"reference_key": "$.foo["},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "not a valid JSONPath expression" in str(exc_info.value)

    def test_build_passes_when_field_present_but_null(self):
        # A field that exists on every item but holds null is a data-quality
        # issue, not a key misconfiguration -> the metric must still build
        # (regression guard: this previously hard-failed the run with a
        # self-contradictory "did not resolve ... available fields: answer"
        # message).
        dataset_items = [{"answer": None}, {"answer": None}]
        metric_fn = MetricFactory.build(
            "equals",
            {"reference_key": "answer"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        assert callable(metric_fn)

    def test_build_does_not_crash_on_non_dict_items(self):
        # Malformed dataset items must yield a clean InvalidMetricError, not an
        # AttributeError from .get()/.keys() on a non-dict.
        dataset_items = [None, "scalar", 42]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "equals",
                {"reference_key": "answer"},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "did not resolve" in str(exc_info.value)

    def test_numerical_similarity_raises_when_references_non_numeric(self):
        # The key resolves for every item, but to non-numeric text -> every item
        # would score 0 (silent flat-0). numerical_similarity must fail loudly
        # (OPIK-7160), unlike equals/levenshtein for which any resolved value is
        # scoreable.
        dataset_items = [{"answer": "positive"}, {"answer": "negative"}]
        with pytest.raises(InvalidMetricError) as exc_info:
            MetricFactory.build(
                "numerical_similarity",
                {"reference_key": "answer"},
                "model",
                dataset_items_provider=lambda: dataset_items,
            )
        assert "no dataset item held a numeric value" in str(exc_info.value)

    def test_numerical_similarity_builds_with_one_numeric_reference(self):
        # Sparse numeric data: only some items are numeric. Build succeeds
        # (there is at least one numeric reference to work with) and the numeric
        # scoring path is actually live -- not a silent flat-0.
        dataset_items = [{"score": 3}, {"score": "n/a"}]
        metric_fn = MetricFactory.build(
            "numerical_similarity",
            {"reference_key": "score"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        assert callable(metric_fn)
        # A single numeric reference means scale_range falls back to 1.0, so an
        # exact match scores 1.0 -- exercising the similarity math, not just the
        # constructor.
        exact = metric_fn({"score": 3}, "3")
        assert exact.value == 1.0
        assert exact.name == "numerical_similarity"
        # A unit-off output is normalized against scale_range=1.0 -> 0.0.
        off_by_one = metric_fn({"score": 3}, "2")
        assert off_by_one.value == 0.0
        # The non-numeric sibling still yields a clean 0 with an explanatory reason.
        non_numeric = metric_fn({"score": "n/a"}, "3")
        assert non_numeric.value == 0.0
        assert "not numeric" in non_numeric.reason


class TestMissingReferencePerItem:
    """Per-item feedback when a reference key is absent on a specific item."""

    def test_equals_missing_reference_scores_zero_with_reason(self):
        metric_fn = MetricFactory.build("equals", {"reference_key": "answer"}, "model")
        result = metric_fn({"other": "x"}, "x")
        assert result.value == 0.0
        assert "Missing reference value" in result.reason
        assert "answer" in result.reason

    def test_levenshtein_missing_reference_scores_zero_with_reason(self):
        metric_fn = MetricFactory.build(
            "levenshtein_ratio", {"reference_key": "answer"}, "model"
        )
        result = metric_fn({"other": "x"}, "x")
        assert result.value == 0.0
        assert "Missing reference value" in result.reason

    def test_present_empty_string_reference_matches_empty_output(self):
        # A field that is present but holds "" is a real reference value, not a
        # missing one: empty output should still score a perfect match. Only a
        # genuinely absent field short-circuits to the missing-reference result.
        metric_fn = MetricFactory.build("equals", {"reference_key": "answer"}, "model")
        result = metric_fn({"answer": ""}, "")
        assert result.value == 1.0


class TestNumericalSimilarityMetric:
    def test_exact_match(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": 0.7}, "0.7")
        assert result.value == 1.0
        assert result.name == "numerical_similarity"

    def test_close_values(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": 0.7}, "0.85")
        # scale_range=1.0 (no dataset), diff=0.15 -> max(0, 1 - 0.15) = 0.85
        assert abs(result.value - 0.85) < 1e-6

    def test_far_values_clamps_to_zero(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": 0.0}, "5.0")
        # scale_range=1.0 (no dataset), diff=5.0 -> max(0, 1 - 5.0) = 0.0
        assert result.value == 0.0

    def test_non_numeric_output(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": 0.7}, "not a number")
        assert result.value == 0.0
        assert "Could not parse" in result.reason

    def test_missing_reference(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "missing"}, "model"
        )
        result = metric_fn({"score": 0.7}, "0.7")
        assert result.value == 0.0
        assert "Missing reference" in result.reason

    def test_non_numeric_reference(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": "not a number"}, "0.7")
        assert result.value == 0.0
        assert "not numeric" in result.reason

    def test_with_jsonpath_reference(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity",
            {"reference_key": "$.feedback_scores[?(@.name == 'Useful')].value"},
            "model",
        )
        dataset_item = {
            "feedback_scores": [
                {"name": "Useful", "value": 0.7},
                {"name": "Usefulness", "value": 0.8},
            ]
        }
        result = metric_fn(dataset_item, "0.85")
        # scale_range=1.0 (no dataset), diff=0.15 -> max(0, 1 - 0.15) = 0.85
        assert abs(result.value - 0.85) < 1e-6

    def test_integer_values(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        result = metric_fn({"score": 1}, "1")
        assert result.value == 1.0

    def test_scale_range_inferred_from_dataset(self):
        dataset_items = [
            {"score": 0},
            {"score": 1},
            {"score": 2},
            {"score": 3},
            {"score": 4},
            {"score": 5},
        ]
        metric_fn = MetricFactory.build(
            "numerical_similarity",
            {"reference_key": "score"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        # scale_range = 5 - 0 = 5
        # ref=4.5, output=4 -> normalized_error = 0.5/5 = 0.1 -> max(0, 1 - 0.1) = 0.9
        result = metric_fn({"score": 4.5}, "4")
        assert abs(result.value - 0.9) < 1e-6

    def test_scale_range_max_error_gives_zero(self):
        dataset_items = [{"score": 0}, {"score": 5}]
        metric_fn = MetricFactory.build(
            "numerical_similarity",
            {"reference_key": "score"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        # scale_range=5, diff=5 -> normalized_error=1.0 -> max(0, 1-1) = 0.0
        result = metric_fn({"score": 0}, "5")
        assert result.value == 0.0

    def test_scale_range_fallback_without_dataset(self):
        metric_fn = MetricFactory.build(
            "numerical_similarity", {"reference_key": "score"}, "model"
        )
        # No provider -> scale_range=1.0 -> max(0, 1 - 0.5) = 0.5
        result = metric_fn({"score": 4.5}, "4")
        assert abs(result.value - 0.5) < 1e-6

    def test_scale_range_single_value_no_range(self):
        dataset_items = [{"score": 3}, {"score": 3}, {"score": 3}]
        metric_fn = MetricFactory.build(
            "numerical_similarity",
            {"reference_key": "score"},
            "model",
            dataset_items_provider=lambda: dataset_items,
        )
        # All same value -> range=0 -> falls back to scale_range=1.0
        # diff=1 -> max(0, 1 - 1.0) = 0.0
        result = metric_fn({"score": 3}, "4")
        assert result.value == 0.0


class TestGEvalTemplateInterpolation:
    """Tests for GEval metric template interpolation with dataset item fields."""

    def test_geval_with_none_params_uses_defaults(self):
        """Test that GEval handles explicit None params without crashing."""
        # Callers may explicitly pass None for optional fields
        params = {"task_introduction": None, "evaluation_criteria": None}
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
            "evaluation_criteria": "Check if output matches {{answer}}",
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
            "evaluation_criteria": "Is the response helpful and accurate?",
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")

        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)

        # Verify no interpolation changes static text
        dataset_item = {"answer": "42"}
        assert (
            _interpolate_template(params["task_introduction"], dataset_item)
            == "Evaluate the response quality"
        )
        assert (
            _interpolate_template(params["evaluation_criteria"], dataset_item)
            == "Is the response helpful and accurate?"
        )

    def test_geval_with_placeholders_in_criteria(self):
        """Test that GEval with {{field}} placeholders in criteria works."""
        from opik_backend.studio.metrics import _interpolate_template

        params = {
            "task_introduction": "Evaluate the response",
            "evaluation_criteria": "Check if the output matches the expected answer: {{answer}}",
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
            "evaluation_criteria": "Is the response accurate?",
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
            "evaluation_criteria": "Expected answer is {{answer}}. Context: {{context}}",
        }
        metric_fn = MetricFactory.build("geval", params, "openai/gpt-4o")

        assert metric_fn.__name__ == "geval"
        assert callable(metric_fn)

        # Verify multiple placeholder interpolation
        dataset_item = {
            "task_type": "homework",
            "domain": "algebra",
            "answer": "x=5",
            "context": "solving equations",
        }
        intro_result = _interpolate_template(params["task_introduction"], dataset_item)
        criteria_result = _interpolate_template(
            params["evaluation_criteria"], dataset_item
        )

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
                    {
                        "role": "user",
                        "content": "What is {{question}}? Answer: {{answer}}",
                    },
                ]
            },
            "llm_model": {"model": "gpt-4o-mini", "parameters": {}},
            "evaluation": {"metrics": [{"type": "equals", "parameters": {}}]},
            "optimizer": {"type": "gepa", "parameters": {}},
        }

        opt_config = OptimizationConfig.from_dict(config)

        # System message should be unchanged (no variables)
        assert opt_config.prompt_messages[0]["content"] == "Be helpful"
        # User message should have converted variables
        assert opt_config.prompt_messages[1]["content"] == "What is {question}? Answer: {answer}"


# ---------------------------------------------------------------------------
# W3a — OptimizationConfig.from_dict raises InvalidConfigError, not raw errors
# ---------------------------------------------------------------------------

def _valid_config(**overrides):
    """Return a minimal valid config dict, with optional field overrides."""
    base = {
        "dataset_name": "my_dataset",
        "prompt": {
            "messages": [{"role": "user", "content": "hello"}]
        },
        "llm_model": {"model": "openai/gpt-4o", "parameters": {}},
        "evaluation": {
            "metrics": [{"type": "equals", "parameters": {}}]
        },
        "optimizer": {"type": "gepa", "parameters": {}},
    }
    base.update(overrides)
    return base


class TestOptimizationConfigFromDictTypedErrors:
    """W3a: from_dict must raise InvalidConfigError (not raw KeyError/ValueError)."""

    def test_missing_dataset_name_raises_invalid_config_error(self):
        config = _valid_config()
        del config["dataset_name"]
        with pytest.raises(InvalidConfigError) as exc_info:
            OptimizationConfig.from_dict(config)
        assert "dataset_name" in str(exc_info.value)

    def test_missing_evaluation_raises_invalid_config_error(self):
        config = _valid_config()
        del config["evaluation"]
        with pytest.raises(InvalidConfigError) as exc_info:
            OptimizationConfig.from_dict(config)
        # The field name 'evaluation' should appear in the error message
        assert "evaluation" in str(exc_info.value)

    def test_empty_metrics_list_raises_invalid_config_error(self):
        config = _valid_config()
        config["evaluation"]["metrics"] = []
        with pytest.raises(InvalidConfigError) as exc_info:
            OptimizationConfig.from_dict(config)
        # Should mention metrics and the reason
        assert "metric" in str(exc_info.value).lower()

    def test_missing_llm_model_key_raises_invalid_config_error(self):
        config = _valid_config()
        del config["llm_model"]
        with pytest.raises(InvalidConfigError) as exc_info:
            OptimizationConfig.from_dict(config)
        assert "llm_model" in str(exc_info.value)

    def test_missing_optimizer_type_raises_invalid_config_error(self):
        config = _valid_config()
        del config["optimizer"]["type"]
        with pytest.raises(InvalidConfigError) as exc_info:
            OptimizationConfig.from_dict(config)
        assert "type" in str(exc_info.value)

    def test_no_raw_key_error_leaks(self):
        """from_dict must never propagate a bare KeyError."""
        config = _valid_config()
        del config["prompt"]
        with pytest.raises(InvalidConfigError):
            OptimizationConfig.from_dict(config)

    def test_no_raw_value_error_leaks(self):
        """from_dict must never propagate a bare ValueError."""
        config = _valid_config()
        config["evaluation"]["metrics"] = []
        with pytest.raises(InvalidConfigError):
            OptimizationConfig.from_dict(config)

    def test_valid_config_parses_without_error(self):
        """Sanity-check: a fully valid config must still succeed."""
        opt_config = OptimizationConfig.from_dict(_valid_config())
        assert opt_config.dataset_name == "my_dataset"
        assert opt_config.metric_type == "equals"
        assert opt_config.optimizer_type == "gepa"


# ---------------------------------------------------------------------------
# W13 — MetricFactory.build raises InvalidMetricError for bad params, not
#         just for unknown types.
# ---------------------------------------------------------------------------

class TestMetricFactoryBadParamsRaisesTypedError:
    """W13: constructor/builder param errors must surface as InvalidMetricError."""

    def test_bad_equals_param_type_raises_invalid_metric_error(self):
        """Passing an unsupported kwarg that the builder itself rejects must
        raise InvalidMetricError, not a raw TypeError."""
        # The _build_equals_metric (and therefore Equals) builder accepts only
        # known params; registering a broken builder is the cleanest injection.
        # Instead we test via a custom registered builder that raises TypeError.
        original_builders = dict(MetricFactory._BUILDERS)
        try:
            @MetricFactory.register("_test_bad_params")
            def _bad_builder(params, model, **kwargs):
                raise TypeError("unexpected keyword argument 'foo'")

            with pytest.raises(InvalidMetricError) as exc_info:
                MetricFactory.build("_test_bad_params", {"foo": "bar"}, "model")
            assert "_test_bad_params" in str(exc_info.value)
            assert "Constructor" in str(exc_info.value) or "Builder" in str(exc_info.value) or "keyword" in str(exc_info.value).lower()
        finally:
            MetricFactory._BUILDERS.clear()
            MetricFactory._BUILDERS.update(original_builders)

    def test_bad_value_in_params_raises_invalid_metric_error(self):
        """A builder that raises ValueError for a bad param value must re-raise
        as InvalidMetricError."""
        original_builders = dict(MetricFactory._BUILDERS)
        try:
            @MetricFactory.register("_test_bad_value")
            def _bad_value_builder(params, model, **kwargs):
                raise ValueError("negative count is not allowed")

            with pytest.raises(InvalidMetricError) as exc_info:
                MetricFactory.build("_test_bad_value", {"count": -1}, "model")
            assert "_test_bad_value" in str(exc_info.value)
        finally:
            MetricFactory._BUILDERS.clear()
            MetricFactory._BUILDERS.update(original_builders)

    def test_invalid_metric_error_from_builder_passes_through(self):
        """An InvalidMetricError raised directly by the builder must not be
        double-wrapped — it passes through as-is."""
        original_builders = dict(MetricFactory._BUILDERS)
        try:
            @MetricFactory.register("_test_passthrough")
            def _passthrough_builder(params, model, **kwargs):
                from opik_backend.studio.exceptions import InvalidMetricError as IME
                raise IME("_test_passthrough", "intentional typed error")

            with pytest.raises(InvalidMetricError) as exc_info:
                MetricFactory.build("_test_passthrough", {}, "model")
            # Should contain the original message, not a re-wrap
            assert "intentional typed error" in str(exc_info.value)
        finally:
            MetricFactory._BUILDERS.clear()
            MetricFactory._BUILDERS.update(original_builders)
