"""Metric factory for Optimization Studio."""

import logging
from typing import Callable, Dict, Any

from opik.evaluation.metrics import (
    Equals,
    GEval,
    LevenshteinRatio,
    StructuredOutputCompliance,
)
from .config import DEFAULT_REFERENCE_KEY, DEFAULT_CASE_SENSITIVE
from .exceptions import InvalidMetricError

logger = logging.getLogger(__name__)


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

