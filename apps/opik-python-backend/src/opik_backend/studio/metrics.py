"""Metric factory for Optimization Studio.

This module provides secure metric execution by reusing the executor infrastructure
from the automations feature (opik_backend.evaluator).

## Security Model

Code metrics execute user-provided Python code, which requires security isolation
appropriate for the deployment environment. This is controlled by the
PYTHON_CODE_EXECUTOR_STRATEGY environment variable:

- 'docker': Full container sandboxing for multi-tenant cloud environments
- 'process': Process isolation for local/self-hosted environments

## Architectural Constraints

Optimization jobs run in isolated subprocesses (via IsolatedSubprocessExecutor)
to prevent memory leaks and crashes from affecting the main Flask server.

For the 'docker' strategy:
- Uses DockerExecutor.run_scoring() for full container sandboxing
- Inherits pre-allocated sandbox environments and configured timeouts

For the 'process' strategy:
- Calls run_user_code() directly instead of ProcessExecutor.run_scoring()
- Why? ProcessExecutor.start_services() spawns its own worker process pool,
  which fails when called from within an already-isolated subprocess due to
  signal handler conflicts and nested process management issues
- run_user_code() provides the same core security logic from process_worker.py
  (exec() in isolated module namespace) without requiring a process pool
- This is appropriate since:
  * Optimization subprocess already provides memory/crash isolation
  * Metrics execute sequentially, so ProcessExecutor's throughput/concurrency
    configs are not relevant in this context
  * Timeout is handled by the outer optimization job supervisor

This hybrid approach matches the PR requirement to "reuse the existing executor
infrastructure" while respecting the discovered architectural constraints of
running within isolated optimization subprocesses.
"""

import logging
import os
import re
from typing import Callable, Dict, List, Any, Optional

import jsonpath
from opik.evaluation.metrics import (
    Equals,
    GEval,
    LevenshteinRatio,
    StructuredOutputCompliance,
)
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.models.litellm.litellm_chat_model import LiteLLMChatModel

from opik_backend.executor import CodeExecutorBase

from .config import DEFAULT_REFERENCE_KEY, DEFAULT_CASE_SENSITIVE
from .exceptions import InvalidMetricError

logger = logging.getLogger(__name__)

_JSONPATH_PATTERN = re.compile(r'[\[$?@]|\.\.')


def _is_jsonpath(key: str) -> bool:
    return bool(_JSONPATH_PATTERN.search(key))


# Sentinel distinguishing "key genuinely absent" from a legitimately resolved
# value that happens to be None (a present-but-null dataset field). Build-time
# validation must not treat the latter as a misconfiguration.
_MISSING = object()


def _resolve_reference(dataset_item: dict, reference_key: str, default=""):
    """Resolve a reference value from a dataset item.

    Supports both plain field names (e.g. "answer") and JSONPath expressions
    (e.g. "$.feedback_scores[?(@.name == 'Useful')].value").
    """
    if not _is_jsonpath(reference_key):
        return dataset_item.get(reference_key, default)

    try:
        matches = jsonpath.findall(reference_key, dataset_item)
        if matches:
            return matches[0]
        return default
    except Exception as e:
        logger.warning(f"JSONPath parse error for '{reference_key}': {e}, falling back to dict lookup")
        return dataset_item.get(reference_key, default)


def _reference_key_resolves(dataset_item: Any, reference_key: str) -> bool:
    """Whether ``reference_key`` resolves to a value on this item.

    A present-but-null value counts as resolved: null is a data-quality issue,
    not a key misconfiguration, so it must not abort the run. Only a genuinely
    absent field (or a JSONPath that matches nothing) is unresolved. Non-dict
    items (malformed data) resolve nothing rather than raising.
    """
    if not isinstance(dataset_item, dict):
        return False
    return _resolve_reference(dataset_item, reference_key, _MISSING) is not _MISSING


def _available_fields_hint(dataset_items: List[Any]) -> str:
    """A ', '-joined sample of top-level field names, for error messages."""
    fields = sorted({
        key
        for item in dataset_items[:20]
        if isinstance(item, dict)
        for key in item.keys()
    })
    return ", ".join(fields) or "(none)"


def _reference_key_error(
    metric_type: str, reference_key: str, dataset_items: List[Any], reason: str
) -> InvalidMetricError:
    """Build the actionable error raised when a reference key is unusable."""
    return InvalidMetricError(
        metric_type,
        f"reference_key '{reference_key}' {reason} (checked "
        f"{len(dataset_items)} dataset items). Available fields: "
        f"{_available_fields_hint(dataset_items)}. Set the reference key to a "
        f"dataset field (or a JSONPath that matches one).",
    )


def _raise_if_malformed_jsonpath(metric_type: str, reference_key: str) -> None:
    """Raise a clear error when a JSONPath-shaped key has invalid syntax.

    ``_resolve_reference`` deliberately swallows JSONPath parse errors and falls
    back to a literal dict lookup, so a field literally named e.g. "user@email"
    (which the ``_is_jsonpath`` heuristic flags because of the '@') still works.
    That same fallback, however, means a *genuinely malformed* JSONPath resolves
    against nothing and would otherwise surface as the generic
    "did not resolve against any dataset item" error, hiding the real cause.

    Callers invoke this only after confirming the key resolved nowhere: a key
    that does resolve (valid JSONPath, or a literal field with special chars)
    never reaches here, so this stays purely additive and never rejects a
    working config. The syntax error is data-independent, so an empty probe is
    enough to trigger it.
    """
    if not _is_jsonpath(reference_key):
        return
    try:
        jsonpath.findall(reference_key, {})
    except Exception as e:
        raise InvalidMetricError(
            metric_type,
            f"reference_key '{reference_key}' is not a valid JSONPath expression: "
            f"{e}. Use a plain dataset field name, or a valid JSONPath such as "
            f"\"$.feedback_scores[?(@.name == 'Useful')].value\".",
        ) from e


def _validate_reference_key_resolves(
    metric_type: str, reference_key: str, dataset_items: List[Any]
) -> None:
    """Fail loudly when a reference key resolves against no dataset item.

    A reference_key that matches no field (a typo, a field absent from the
    dataset, or a JSONPath that matches nothing) is the silent-failure this
    guards against: reference-based metrics would otherwise score every item
    0, the optimizer would beat nothing and return the seed prompt, and the
    run would report "completed" with no sign anything was misconfigured
    (OPIK-7160). Raising here surfaces the misconfiguration as a failed run
    with an actionable message and keeps that failure distinguishable from a
    legitimate "no improvement over baseline" run (OPIK-7038).

    Only a *total* mismatch (zero items resolve) raises — sparse data where
    some items lack the key is handled per-item at scoring time. An empty list
    (no dataset available) skips the check rather than guessing.

    Args:
        metric_type: Metric identifier, used in the error message.
        reference_key: The configured reference key (field name or JSONPath).
        dataset_items: Materialized dataset items to validate against.

    Raises:
        InvalidMetricError: If no dataset item resolves the reference key.
    """
    if not dataset_items:
        return
    if any(_reference_key_resolves(item, reference_key) for item in dataset_items):
        return
    # Nothing resolved: a malformed JSONPath is the more specific cause, so
    # report it before the generic "no field matched" message.
    _raise_if_malformed_jsonpath(metric_type, reference_key)
    raise _reference_key_error(
        metric_type, reference_key, dataset_items,
        "did not resolve against any dataset item",
    )


def _missing_reference_result(metric_name: str, reference_key: str) -> "ScoreResult":
    """Per-item result when a reference is absent/null on a specific item.

    Sparse data is legitimate (the key resolved for other items, so the metric
    built), but this item can't be scored — surface that instead of silently
    treating the missing value as an empty-string match.
    """
    logger.warning(
        f"{metric_name}: missing reference value for key '{reference_key}' "
        f"on a dataset item; scoring 0"
    )
    return ScoreResult(
        value=0.0,
        name=metric_name,
        reason=f"Missing reference value for key '{reference_key}'",
    )


# Environment variable to control execution strategy (same as evaluator.py)
EXECUTION_STRATEGY = os.getenv("PYTHON_CODE_EXECUTOR_STRATEGY", "process")

# Singleton executor for code metrics (only for docker strategy)
_code_metric_executor: Optional[CodeExecutorBase] = None


def _run_code_metric(code: str, data: dict) -> dict:
    """Execute code metric using the appropriate executor strategy.
    
    Reuses the automations evaluator infrastructure (opik_backend.evaluator) while
    respecting the architectural constraints of running in optimization subprocesses.
    
    Security Strategy (controlled by PYTHON_CODE_EXECUTOR_STRATEGY env var):
    
    'docker' strategy (multi-tenant environments):
      - Uses DockerExecutor.run_scoring() for full container sandboxing
      - Inherits pre-allocated sandbox environments
      - Configured timeouts, concurrency via env vars
      - Same executor used by opik_backend.evaluator.execute_evaluator_python
    
    'process' strategy (local/self-hosted environments):
      - Uses run_user_code() directly from opik_backend.process_worker
      - Same core security logic as ProcessExecutor (exec in isolated namespace)
      - Why not ProcessExecutor.run_scoring()?
        * Optimization jobs run in isolated subprocesses (IsolatedSubprocessExecutor)
        * ProcessExecutor.start_services() spawns worker process pool
        * Nested process pools fail with signal handler conflicts
        * run_user_code() provides same isolation without nested pools
        * ProcessExecutor's throughput/concurrency configs are irrelevant since
          metrics execute sequentially in the optimization subprocess
    
    This hybrid approach reuses the executor infrastructure while respecting
    the architectural reality that optimization jobs run in isolated subprocesses.
    
    Args:
        code: Python code containing a BaseMetric subclass
        data: Dictionary with 'output' (LLM response) and dataset_item fields
        
    Returns:
        Response dict with 'scores' list on success, or 'error' key on failure
    """
    global _code_metric_executor
    
    if EXECUTION_STRATEGY == "docker":
        # Multi-tenant: Use DockerExecutor for full container sandboxing
        if _code_metric_executor is None:
            from opik_backend.executor_docker import DockerExecutor
            _code_metric_executor = DockerExecutor()
            logger.info("Created DockerExecutor for code metrics (multi-tenant isolation)")
        return _code_metric_executor.run_scoring(code, data)
    
    else:
        # Local/self-hosted (process strategy): Use run_user_code directly
        # This is the same core logic used by ProcessExecutor, but without creating
        # a worker pool (which would fail in the nested subprocess context)
        from opik_backend.process_worker import run_user_code
        return run_user_code(code, data)


class MetricFactory:
    """Factory for creating metric functions from config.
    
    Uses a registry pattern to allow easy addition of new metrics.
    Each metric builder is registered with the @MetricFactory.register decorator.
    """
    
    _BUILDERS: Dict[str, Callable] = {}
    
    @classmethod
    def register(cls, metric_type: str):
        """Decorator to register metric builders.
        
        Args:
            metric_type: The metric type identifier (e.g., "equals", "geval")
            
        Returns:
            Decorator function
            
        Example:
            @MetricFactory.register("my_metric")
            def _build_my_metric(params: Dict[str, Any], model: str):
                # Build and return metric function
                pass
        """
        def decorator(func):
            cls._BUILDERS[metric_type] = func
            return func
        return decorator
    
    @classmethod
    def build(cls, metric_type: str, metric_params: Dict[str, Any], model: str,
              dataset_items_provider: Callable[[], List[Dict[str, Any]]] = None) -> Callable:
        """Build a metric function from config.
        
        Args:
            metric_type: The type of metric to build
            metric_params: Parameters for the metric
            model: LLM model identifier (required for LLM-based metrics)
            dataset_items_provider: Optional zero-arg callable that returns
                dataset items. Invoked at build time by metrics that inspect
                the data: numerical_similarity for scale inference, and all
                reference-based metrics (equals, levenshtein_ratio,
                numerical_similarity) to validate that reference_key resolves
                against the dataset. When None, that validation is skipped.
                Not invoked by metrics that ignore the dataset at build time
                (e.g. geval, json_schema_validator, code).

        Returns:
            A callable metric function(dataset_item, llm_output) -> ScoreResult

        Raises:
            InvalidMetricError: If metric_type is not registered, or a
                reference-based metric's reference_key resolves against no
                dataset item.
        """
        if metric_type not in cls._BUILDERS:
            available = ", ".join(sorted(cls._BUILDERS.keys()))
            raise InvalidMetricError(
                metric_type,
                f"Available metrics: {available}"
            )
        
        logger.debug(f"Building metric: {metric_type} with params: {metric_params}")
        metric_fn = cls._BUILDERS[metric_type](
            metric_params, model, dataset_items_provider=dataset_items_provider,
        )
        logger.debug(f"Created metric function: {metric_fn.__name__}")
        return metric_fn


def _build_reference_metric(
    metric_type: str,
    opik_metric: Any,
    reason_for: Callable[[float], str],
    params: Dict[str, Any],
    **kwargs,
) -> Callable:
    """Build a reference-based string metric (equals / levenshtein_ratio).

    Both metrics share the same scaffolding — resolve ``reference_key`` from the
    config, validate it against the dataset at build time, and at scoring time
    resolve the per-item reference, short-circuit to a missing-reference result
    when it is absent, then delegate to the underlying opik metric. Only the
    opik metric instance and the human-readable ``reason`` differ, so those are
    the parameters; everything else lives here so the two stay in lock-step.

    Args:
        metric_type: Metric identifier, used for validation errors, the missing
            reference result, and ``metric_fn.__name__``.
        opik_metric: A pre-built opik metric exposing ``score(reference, output)``.
        reason_for: Maps the resulting score value to a human-readable reason.
        params: Metric parameters (reads ``reference_key``).
    """
    reference_key = params.get("reference_key", DEFAULT_REFERENCE_KEY)
    dataset_items_provider = kwargs.get("dataset_items_provider")
    if dataset_items_provider is not None:
        _validate_reference_key_resolves(
            metric_type, reference_key, list(dataset_items_provider())
        )

    def metric_fn(dataset_item, llm_output):
        reference = _resolve_reference(dataset_item, reference_key, None)
        if reference is None:
            return _missing_reference_result(metric_type, reference_key)
        result = opik_metric.score(reference=reference, output=llm_output)
        return ScoreResult(
            value=result.value, name=result.name, reason=reason_for(result.value)
        )

    metric_fn.__name__ = metric_type
    return metric_fn


@MetricFactory.register("equals")
def _build_equals_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Build an Equals metric function.

    Compares output with reference from dataset using exact string match.

    Args:
        params: Metric parameters
            - case_sensitive (bool): Whether comparison is case-sensitive
            - reference_key (str): Key in dataset item containing reference value
        model: LLM model (not used for this metric)

    Returns:
        Metric function
    """
    case_sensitive = params.get("case_sensitive", DEFAULT_CASE_SENSITIVE)
    return _build_reference_metric(
        "equals",
        Equals(case_sensitive=case_sensitive),
        lambda value: (
            "Exact match: output equals reference"
            if value == 1.0
            else "No match: output does not equal reference"
        ),
        params,
        **kwargs,
    )


@MetricFactory.register("levenshtein_ratio")
def _build_levenshtein_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Build a LevenshteinRatio metric function.

    Computes string similarity using Levenshtein distance.

    Args:
        params: Metric parameters
            - case_sensitive (bool): Whether comparison is case-sensitive
            - reference_key (str): Key in dataset item containing reference value
        model: LLM model (not used for this metric)

    Returns:
        Metric function
    """
    case_sensitive = params.get("case_sensitive", DEFAULT_CASE_SENSITIVE)
    return _build_reference_metric(
        "levenshtein_ratio",
        LevenshteinRatio(case_sensitive=case_sensitive),
        lambda value: f"Similarity: {value * 100:.0f}%",
        params,
        **kwargs,
    )


def _interpolate_template(template: str, dataset_item: Dict[str, Any]) -> str:
    """Interpolate dataset item fields into a template string.
    
    Replaces {{field_name}} placeholders with values from the dataset item.
    If a field is not found, the placeholder is left unchanged.
    
    Supports field names with alphanumerics, underscores, dots, and hyphens
    (e.g., {{answer}}, {{user.name}}, {{answer-key}}).
    
    Args:
        template: String containing {{field_name}} placeholders
        dataset_item: Dictionary of dataset item fields
        
    Returns:
        Interpolated string with placeholders replaced by values
        
    Example:
        >>> _interpolate_template("Expected: {{answer}}", {"answer": "42"})
        "Expected: 42"
    """
    import re
    
    def replace_match(match):
        field_name = match.group(1).strip()
        if field_name in dataset_item:
            return str(dataset_item[field_name])
        return match.group(0)  # Leave unchanged if not found
    
    # Match field names with alphanumerics, underscores, dots, and hyphens
    return re.sub(r'\{\{\s*([\w.-]+)\s*\}\}', replace_match, template)


def _has_template_placeholders(text: str) -> bool:
    """Check if text contains {{field_name}} template placeholders."""
    import re
    return bool(re.search(r'\{\{', text))


@MetricFactory.register("geval")
def _build_geval_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Build a GEval metric function.
    
    Uses an LLM to evaluate outputs based on custom criteria.
    Supports dataset item field interpolation using {{field_name}} syntax
    in task_introduction and evaluation_criteria.
    
    Args:
        params: Metric parameters
            - task_introduction (str): Description of the task being evaluated.
              Can include {{field_name}} placeholders for dataset item fields.
            - evaluation_criteria (str): Criteria for evaluation.
              Can include {{field_name}} placeholders for dataset item fields.
        model: LLM model to use for evaluation
        
    Returns:
        Metric function
        
    Example:
        With evaluation_criteria="Check if output matches expected: {{answer}}"
        and dataset_item={"answer": "Paris"}, the LLM judge will see:
        "Check if output matches expected: Paris"
    """
    # Normalize nullable params to strings - callers may pass explicit None
    task_intro_template = params.get("task_introduction") or "Evaluate the output"
    eval_criteria_template = params.get("evaluation_criteria") or ""
    
    # Check if templates contain placeholders that need runtime interpolation
    needs_interpolation = (
        _has_template_placeholders(task_intro_template) or 
        _has_template_placeholders(eval_criteria_template)
    )
    
    llm_model = LiteLLMChatModel(model_name=model, stream=False)

    if not needs_interpolation:
        # No placeholders - create single reusable GEval instance (original behavior)
        geval_metric = GEval(
            task_introduction=task_intro_template,
            evaluation_criteria=eval_criteria_template,
            model=llm_model
        )
        
        def metric_fn(dataset_item, llm_output):
            # Note: GEval.score() only accepts 'output' - dataset_item context is embedded
            # in task_introduction/evaluation_criteria via template interpolation, not passed
            # separately. GEval's score() ignores any extra kwargs (**ignored_kwargs).
            return geval_metric.score(output=llm_output)
        
        metric_fn.__name__ = "geval"
        return metric_fn
    
    # Has placeholders - interpolate at evaluation time
    # Note: Each unique interpolated criteria will generate its own chain-of-thought,
    # which is cached by GEval. This is expected behavior for customized evaluation.
    logger.info("GEval metric using template interpolation for dataset item fields")
    
    def metric_fn_with_interpolation(dataset_item, llm_output):
        # Normalize dataset_item to prevent TypeError if None is passed
        item = dataset_item or {}
        # Interpolate dataset item fields into templates
        task_intro = _interpolate_template(task_intro_template, item)
        eval_criteria = _interpolate_template(eval_criteria_template, item)
        
        # Create GEval with interpolated values
        # GEval internally caches chain-of-thought by (task_intro, criteria, model)
        geval_metric = GEval(
            task_introduction=task_intro,
            evaluation_criteria=eval_criteria,
            model=llm_model
        )
        
        # Note: GEval.score() only accepts 'output' - dataset_item context is already
        # embedded in task_introduction/evaluation_criteria above via interpolation.
        # GEval's score() ignores any extra kwargs (**ignored_kwargs).
        return geval_metric.score(output=llm_output)
    
    metric_fn_with_interpolation.__name__ = "geval"
    return metric_fn_with_interpolation


@MetricFactory.register("json_schema_validator")
def _build_json_schema_validator_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Build a JSON Schema Validator metric function.
    
    Validates that the LLM output complies with a JSON schema from the dataset item.
    
    Args:
        params: Metric parameters
            - schema_key (str): Key in dataset item containing the JSON schema (default: "json_schema")
        model: LLM model to use for validation
        
    Returns:
        Metric function
    """
    schema_key = params.get("schema_key", "json_schema")
    
    llm_model = LiteLLMChatModel(model_name=model, stream=False)
    structured_metric = StructuredOutputCompliance(
        model=llm_model,
        name="json_schema_validator"
    )
    
    def metric_fn(dataset_item, llm_output):
        schema = dataset_item.get(schema_key)
        if not schema:
            from opik.evaluation.metrics.score_result import ScoreResult
            return ScoreResult(
                value=0.0, 
                name="json_schema_validator", 
                reason=f"Missing schema in dataset item key '{schema_key}'"
            )
        return structured_metric.score(
            output=llm_output,
            schema=schema
        )
    
    metric_fn.__name__ = "json_schema_validator"
    return metric_fn


@MetricFactory.register("numerical_similarity")
def _build_numerical_similarity_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Normalized similarity between a numeric LLM output and a reference value.

    Score = max(0, 1 - |output - reference| / scale_range)

    The scale_range is inferred from the dataset via ``dataset_items_provider``
    (max - min of parseable reference values).  When the provider is absent, the
    dataset has fewer than 2 numeric references, or all references are identical,
    scale_range defaults to 1.0 (raw absolute-error mode).

    Non-numeric LLM outputs or missing/non-numeric references yield a score of
    0.0 with an explanatory reason string.

    Based on normalised absolute error:
    https://en.wikipedia.org/wiki/Mean_absolute_error#Normalized_mean_absolute_error
    """
    from opik.evaluation.metrics.score_result import ScoreResult

    reference_key = params.get("reference_key", DEFAULT_REFERENCE_KEY)

    # Infer the value range from dataset reference values so the error is
    # normalized relative to the scale (e.g., 0-5).  Falls back to 1.0 when the
    # provider is absent, or the dataset has fewer than 2 distinct numeric
    # references.  This single pass over the dataset also validates the key:
    # unlike equals/levenshtein a resolved-but-non-numeric reference is still a
    # dead metric (every item scores 0), so numerical_similarity fails loudly
    # when no item yields a numeric value, not merely when the key is absent
    # (OPIK-7160).
    scale_range = 1.0
    dataset_items_provider = kwargs.get("dataset_items_provider")
    if dataset_items_provider is not None:
        items = list(dataset_items_provider())
        if items:
            resolved_any = False
            ref_values = []
            for item in items:
                raw = _resolve_reference(item, reference_key, _MISSING)
                if raw is _MISSING:
                    continue
                resolved_any = True
                if raw is None:
                    continue
                try:
                    ref_values.append(float(raw))
                except (ValueError, TypeError):
                    pass
            if not resolved_any:
                _raise_if_malformed_jsonpath("numerical_similarity", reference_key)
                raise _reference_key_error(
                    "numerical_similarity", reference_key, items,
                    "did not resolve against any dataset item",
                )
            if not ref_values:
                raise _reference_key_error(
                    "numerical_similarity", reference_key, items,
                    "resolved but no dataset item held a numeric value",
                )
            if len(ref_values) >= 2:
                inferred = max(ref_values) - min(ref_values)
                if inferred > 0:
                    scale_range = inferred
                    logger.info(
                        f"numerical_similarity: inferred scale_range={scale_range} "
                        f"from {len(ref_values)} reference values"
                    )

    def metric_fn(dataset_item, llm_output):
        reference = _resolve_reference(dataset_item, reference_key, None)

        try:
            output_val = float(str(llm_output).strip())
        except (ValueError, TypeError):
            return ScoreResult(
                value=0.0,
                name="numerical_similarity",
                reason=f"Could not parse LLM output as number: {str(llm_output)[:100]}"
            )

        if reference is None:
            return _missing_reference_result("numerical_similarity", reference_key)

        try:
            reference_val = float(reference)
        except (ValueError, TypeError):
            return ScoreResult(
                value=0.0,
                name="numerical_similarity",
                reason=f"Reference value is not numeric: {str(reference)[:100]}"
            )

        normalized_error = abs(output_val - reference_val) / scale_range
        score = max(0.0, 1.0 - normalized_error)

        return ScoreResult(
            value=score,
            name="numerical_similarity",
            reason=f"output={output_val}, reference={reference_val}, "
                   f"abs_error={abs(output_val - reference_val):.4f}, scale_range={scale_range}"
        )

    metric_fn.__name__ = "numerical_similarity"
    return metric_fn


@MetricFactory.register("code")
def _build_code_metric(params: Dict[str, Any], model: str, **kwargs) -> Callable:
    """Build a custom code metric function using the secure executor infrastructure.
    
    User code is executed using the same executor infrastructure as the automations
    evaluator, providing security isolation appropriate for the deployment environment:
    - DockerExecutor: Full container sandboxing for multi-tenant environments
    - ProcessExecutor: Process isolation with pre-warmed worker pools for local/OSS
    
    The executor type is determined by PYTHON_CODE_EXECUTOR_STRATEGY env var and
    inherits all configuration (timeouts, concurrency, etc.) from the executor setup.
    
    Code must define a BaseMetric subclass:
    
        from opik.evaluation.metrics import BaseMetric
        from opik.evaluation.metrics.score_result import ScoreResult
        
        class MyMetric(BaseMetric):
            def __init__(self, name: str = "my_metric"):
                super().__init__(name=name)
            
            def score(self, output: str, **kwargs) -> ScoreResult:
                # output: the LLM response
                # kwargs: contains dataset_item fields
                return ScoreResult(
                    name=self.name,
                    value=1.0,
                    reason="Evaluation reason"
                )
    
    Args:
        params: Metric parameters
            - code (str): Python code containing a BaseMetric subclass
        model: LLM model (not used for this metric)
        
    Returns:
        Metric function with signature (dataset_item, llm_output) -> ScoreResult
        
    Raises:
        InvalidMetricError: If code is missing or invalid
    """
    code = params.get("code")
    if not code:
        raise InvalidMetricError("code", "Missing 'code' parameter for code metric")
    
    logger.info(f"Building code metric (code length: {len(code)} chars)")
    
    # Validate code and extract metric name by running it once with dummy data
    # This gets the actual metric.name attribute from the instantiated class
    # Uses the same secure executor infrastructure (Docker or Process) as actual scoring
    try:
        # Do a quick validation run with dummy data to extract the metric name
        validation_response = _run_code_metric(code, {"output": ""})
        
        if "error" in validation_response:
            raise InvalidMetricError("code", f"Invalid Python code: {validation_response['error']}")
        
        # Extract the metric name from the first score result
        scores = validation_response.get("scores", [])
        if scores and scores[0].get("name"):
            metric_name = scores[0]["name"]
        else:
            metric_name = "code"
        logger.info(f"Extracted metric name from class: {metric_name}")
        
    except InvalidMetricError:
        raise
    except Exception as e:
        raise InvalidMetricError("code", f"Failed to validate metric code: {e}")
    
    def isolated_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
        """Execute the metric using the configured executor strategy."""
        # Merge data: output + dataset_item fields
        # This matches metric.score(**data) interface in process_worker.py
        data = {"output": llm_output, **dataset_item}
        
        logger.debug(f"Executing code metric with data keys: {list(data.keys())}")
        
        # Execute using the executor infrastructure (same as automations evaluator)
        # This respects PYTHON_CODE_EXECUTOR_STRATEGY for appropriate security level
        response = _run_code_metric(code, data)
        
        if "error" in response:
            error_msg = response.get('error', 'Unknown error')
            logger.warning(f"Code metric error: {error_msg}")
            return ScoreResult(
                name="code",
                value=0.0,
                reason=f"Error: {error_msg[:200]}"
            )
        
        scores = response.get("scores", [])
        if not scores:
            logger.warning("Code metric returned no scores")
            return ScoreResult(
                name="code",
                value=0.0,
                reason="No ScoreResult returned by metric"
            )
        
        # Return first score (studio expects single score)
        # The metric name is preserved from the user's BaseMetric class
        score = scores[0]
        logger.debug(f"Code metric returned score: name={score.get('name')}, value={score.get('value')}")
        return ScoreResult(
            name=score.get("name"),
            value=score.get("value", 0.0),
            reason=score.get("reason", "")
        )
    
    isolated_metric.__name__ = metric_name
    return isolated_metric
