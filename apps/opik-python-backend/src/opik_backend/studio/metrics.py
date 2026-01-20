"""Metric factory for Optimization Studio."""

import inspect
import logging
import os
import uuid
from concurrent.futures import ProcessPoolExecutor, TimeoutError as FuturesTimeoutError
from types import ModuleType, FunctionType
from typing import Callable, Dict, Any, Optional

from opik.evaluation.metrics import (
    BaseMetric,
    Equals,
    GEval,
    LevenshteinRatio,
    StructuredOutputCompliance,
)
from opik.evaluation.metrics.score_result import ScoreResult
from .config import DEFAULT_REFERENCE_KEY, DEFAULT_CASE_SENSITIVE
from .exceptions import InvalidMetricError

logger = logging.getLogger(__name__)

# Timeout for code metric execution (seconds)
CODE_METRIC_TIMEOUT = int(os.getenv("OPIK_CODE_METRIC_TIMEOUT_SECS", "30"))


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
        
        logger.info(f"Building metric: {metric_type} with params: {metric_params}")
        metric_fn = cls._BUILDERS[metric_type](metric_params, model)
        logger.info(f"Created metric function: {metric_fn.__name__}")
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


@MetricFactory.register("geval")
def _build_geval_metric(params: Dict[str, Any], model: str) -> Callable:
    """Build a GEval metric function.
    
    Uses an LLM to evaluate outputs based on custom criteria.
    
    Args:
        params: Metric parameters
            - task_introduction (str): Description of the task being evaluated
            - evaluation_criteria (str): Criteria for evaluation
        model: LLM model to use for evaluation
        
    Returns:
        Metric function
    """
    task_intro = params.get("task_introduction", "Evaluate the output")
    eval_criteria = params.get("evaluation_criteria", "")
    geval_metric = GEval(
        task_introduction=task_intro,
        evaluation_criteria=eval_criteria,
        model=model
    )
    
    def metric_fn(dataset_item, llm_output):
        return geval_metric.score(
            input=dataset_item,
            output=llm_output
        )
    
    metric_fn.__name__ = "geval"
    return metric_fn


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


def _execute_code_metric_in_process(
    code: str, 
    dataset_item: Dict[str, Any], 
    llm_output: str
) -> Dict[str, Any]:
    """Execute user code metric in an isolated process.
    
    This function runs in a separate process via ProcessPoolExecutor.
    It follows the same pattern as the automations evaluator for security isolation.
    
    Args:
        code: The user's Python code containing the metric
        dataset_item: The dataset item dict
        llm_output: The LLM output string
        
    Returns:
        Dict with either:
        - {"score": {"name": str, "value": float, "reason": str}} on success
        - {"error": str} on failure
    """
    import traceback
    from types import ModuleType, FunctionType
    import inspect
    import uuid
    
    from opik.evaluation.metrics import BaseMetric
    from opik.evaluation.metrics.score_result import ScoreResult
    
    # Well-known metric function names
    PREFERRED_METRIC_NAMES = ['evaluation_metric', 'score', 'metric', 'evaluate']
    
    try:
        # Create isolated module for code execution
        module = ModuleType(str(uuid.uuid4()))
        
        # Pre-import commonly used modules
        module.__dict__["json"] = __import__("json")
        module.__dict__["re"] = __import__("re")
        module.__dict__["math"] = __import__("math")
        module.__dict__["ScoreResult"] = ScoreResult
        module.__dict__["BaseMetric"] = BaseMetric
        
        pre_exec_names = set(module.__dict__.keys())
        
        # Execute user code
        exec(code, module.__dict__)
        
        # Find metric (class or function)
        new_names = [n for n in module.__dict__.keys() if n not in pre_exec_names]
        
        metric_class = None
        metric_fn_candidates = []
        
        for name in new_names:
            obj = module.__dict__[name]
            
            if inspect.isclass(obj) and issubclass(obj, BaseMetric) and obj is not BaseMetric:
                metric_class = obj
                break
            
            if isinstance(obj, FunctionType) and not name.startswith("_"):
                try:
                    sig = inspect.signature(obj)
                    if len(list(sig.parameters.keys())) >= 2:
                        metric_fn_candidates.append((name, obj))
                except (ValueError, TypeError):
                    continue
        
        # Execute the metric
        result = None
        
        if metric_class is not None:
            metric_instance = metric_class()
            result = metric_instance.score(output=llm_output, **dataset_item)
        elif metric_fn_candidates:
            # Select function deterministically
            selected_fn = None
            for preferred_name in PREFERRED_METRIC_NAMES:
                for name, fn in metric_fn_candidates:
                    if name == preferred_name:
                        selected_fn = fn
                        break
                if selected_fn:
                    break
            
            if selected_fn is None:
                if len(metric_fn_candidates) == 1:
                    selected_fn = metric_fn_candidates[0][1]
                else:
                    return {"error": f"Multiple metric functions found: {[n for n, _ in metric_fn_candidates]}"}
            
            result = selected_fn(dataset_item, llm_output)
        else:
            return {"error": "No metric function or BaseMetric class found in code"}
        
        # Coerce result to ScoreResult format
        if isinstance(result, ScoreResult):
            return {"score": {"name": result.name, "value": result.value, "reason": result.reason or ""}}
        elif isinstance(result, (int, float)):
            value = max(0.0, min(1.0, float(result)))
            return {"score": {"name": "code", "value": value, "reason": ""}}
        elif isinstance(result, dict) and "value" in result:
            value = max(0.0, min(1.0, float(result["value"])))
            return {"score": {"name": result.get("name", "code"), "value": value, "reason": result.get("reason", "")}}
        else:
            return {"error": f"Metric returned unsupported type: {type(result).__name__}"}
            
    except Exception as e:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[-5:])
        return {"error": f"Code metric execution failed: {e}\n{stacktrace}"}


# Singleton process pool for code metric execution
_code_metric_executor: Optional[ProcessPoolExecutor] = None


def _get_code_metric_executor() -> ProcessPoolExecutor:
    """Get or create the process pool executor for code metrics.
    
    Uses a singleton pattern to reuse the pool across metric evaluations.
    """
    global _code_metric_executor
    if _code_metric_executor is None:
        # Use 1 worker - metrics are called sequentially by the optimizer
        # This provides isolation without excessive process overhead
        _code_metric_executor = ProcessPoolExecutor(max_workers=1)
        logger.info("Created ProcessPoolExecutor for code metric isolation")
    return _code_metric_executor


@MetricFactory.register("code")
def _build_code_metric(params: Dict[str, Any], model: str) -> Callable:
    """Build a custom code metric function with process isolation.
    
    User code is executed in an isolated subprocess for security, following
    the same pattern as the automations evaluator. This provides:
    - Memory isolation (separate address space)
    - Crash isolation (subprocess failure doesn't affect optimizer)
    - Resource limits via timeout
    
    Supports two patterns:
    
    1. Function pattern (optimizer-style):
       def my_metric(dataset_item, llm_output):
           return ScoreResult(name="...", value=0.5)
    
    2. Class pattern (BaseMetric-style, same as automations):
       class MyMetric(BaseMetric):
           def score(self, output, **kwargs):
               return ScoreResult(name="...", value=0.5)
    
    Args:
        params: Metric parameters
            - code (str): Python code containing the metric function or class
        model: LLM model (not used for this metric)
        
    Returns:
        Metric function with signature (dataset_item, llm_output) -> ScoreResult
        
    Raises:
        InvalidMetricError: If code is missing or invalid
    """
    code = params.get("code")
    if not code:
        raise InvalidMetricError("code", "Missing 'code' parameter for code metric")
    
    logger.info(f"Building code metric with process isolation (code length: {len(code)} chars)")
    
    # Validate code can be parsed (fast fail before running optimization)
    try:
        compile(code, "<code_metric>", "exec")
        logger.info("Code metric syntax validated successfully")
    except SyntaxError as e:
        raise InvalidMetricError("code", f"Invalid Python code: {e}")
    
    def isolated_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
        """Execute the metric in an isolated subprocess."""
        executor = _get_code_metric_executor()
        
        try:
            # Submit to process pool and wait with timeout
            future = executor.submit(
                _execute_code_metric_in_process,
                code,
                dataset_item,
                llm_output
            )
            result = future.result(timeout=CODE_METRIC_TIMEOUT)
            
            if "error" in result:
                logger.warning(f"Code metric error: {result['error']}")
                # Return zero score on error rather than crashing the optimization
                return ScoreResult(
                    name="code",
                    value=0.0,
                    reason=f"Metric error: {result['error'][:200]}"
                )
            
            score_data = result["score"]
            return ScoreResult(
                name=score_data["name"],
                value=score_data["value"],
                reason=score_data["reason"]
            )
            
        except FuturesTimeoutError:
            logger.error(f"Code metric timed out after {CODE_METRIC_TIMEOUT}s")
            return ScoreResult(
                name="code",
                value=0.0,
                reason=f"Metric execution timed out after {CODE_METRIC_TIMEOUT}s"
            )
        except Exception as e:
            logger.error(f"Code metric execution failed: {e}")
            return ScoreResult(
                name="code",
                value=0.0,
                reason=f"Metric execution failed: {str(e)[:200]}"
            )
    
    isolated_metric.__name__ = "code"
    return isolated_metric
