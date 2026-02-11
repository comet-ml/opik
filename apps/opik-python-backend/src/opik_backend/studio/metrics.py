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
from typing import Callable, Dict, Any, Optional

from opik.evaluation.metrics import (
    Equals,
    GEval,
    LevenshteinRatio,
    StructuredOutputCompliance,
)
from opik.evaluation.metrics.score_result import ScoreResult

from opik_backend.executor import CodeExecutorBase

from .config import DEFAULT_REFERENCE_KEY, DEFAULT_CASE_SENSITIVE
from .exceptions import InvalidMetricError

logger = logging.getLogger(__name__)

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
    def build(cls, metric_type: str, metric_params: Dict[str, Any], model: str) -> Callable:
        """Build a metric function from config.
        
        Args:
            metric_type: The type of metric to build
            metric_params: Parameters for the metric
            model: LLM model identifier (required for LLM-based metrics)
            
        Returns:
            A callable metric function(dataset_item, llm_output) -> ScoreResult
            
        Raises:
            InvalidMetricError: If metric_type is not registered
        """
        if metric_type not in cls._BUILDERS:
            available = ", ".join(sorted(cls._BUILDERS.keys()))
            raise InvalidMetricError(
                metric_type,
                f"Available metrics: {available}"
            )
        
        logger.debug(f"Building metric: {metric_type} with params: {metric_params}")
        metric_fn = cls._BUILDERS[metric_type](metric_params, model)
        logger.debug(f"Created metric function: {metric_fn.__name__}")
        return metric_fn


@MetricFactory.register("equals")
def _build_equals_metric(params: Dict[str, Any], model: str) -> Callable:
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
    from opik.evaluation.metrics.score_result import ScoreResult
    
    case_sensitive = params.get("case_sensitive", DEFAULT_CASE_SENSITIVE)
    reference_key = params.get("reference_key", DEFAULT_REFERENCE_KEY)
    equals_metric = Equals(case_sensitive=case_sensitive)
    
    def metric_fn(dataset_item, llm_output):
        reference = dataset_item.get(reference_key, "")
        result = equals_metric.score(reference=reference, output=llm_output)
        
        # Add reason for hierarchical_reflective optimizer compatibility
        if result.value == 1.0:
            reason = "Exact match: output equals reference"
        else:
            reason = "No match: output does not equal reference"
        
        return ScoreResult(value=result.value, name=result.name, reason=reason)
    
    metric_fn.__name__ = "equals"
    return metric_fn


@MetricFactory.register("levenshtein_ratio")
def _build_levenshtein_metric(params: Dict[str, Any], model: str) -> Callable:
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
    from opik.evaluation.metrics.score_result import ScoreResult
    
    case_sensitive = params.get("case_sensitive", DEFAULT_CASE_SENSITIVE)
    reference_key = params.get("reference_key", DEFAULT_REFERENCE_KEY)
    levenshtein_metric = LevenshteinRatio(case_sensitive=case_sensitive)
    
    def metric_fn(dataset_item, llm_output):
        reference = dataset_item.get(reference_key, "")
        result = levenshtein_metric.score(reference=reference, output=llm_output)
        
        reason = f"Similarity: {result.value * 100:.0f}%"
        return ScoreResult(value=result.value, name=result.name, reason=reason)
    
    metric_fn.__name__ = "levenshtein_ratio"
    return metric_fn


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
def _build_geval_metric(params: Dict[str, Any], model: str) -> Callable:
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
    
    if not needs_interpolation:
        # No placeholders - create single reusable GEval instance (original behavior)
        geval_metric = GEval(
            task_introduction=task_intro_template,
            evaluation_criteria=eval_criteria_template,
            model=model
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
            model=model
        )
        
        # Note: GEval.score() only accepts 'output' - dataset_item context is already
        # embedded in task_introduction/evaluation_criteria above via interpolation.
        # GEval's score() ignores any extra kwargs (**ignored_kwargs).
        return geval_metric.score(output=llm_output)
    
    metric_fn_with_interpolation.__name__ = "geval"
    return metric_fn_with_interpolation


@MetricFactory.register("json_schema_validator")
def _build_json_schema_validator_metric(params: Dict[str, Any], model: str) -> Callable:
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
    
    structured_metric = StructuredOutputCompliance(
        model=model,
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


@MetricFactory.register("code")
def _build_code_metric(params: Dict[str, Any], model: str) -> Callable:
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
