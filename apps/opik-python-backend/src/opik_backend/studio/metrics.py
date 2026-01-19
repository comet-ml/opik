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


import os
import re

# Environment variable to control code metric availability
# Set to "true" to enable custom code metrics (requires proper isolation)
CODE_METRIC_ENABLED = os.getenv("OPIK_CODE_METRIC_ENABLED", "true").lower() == "true"

# Patterns that indicate potentially dangerous code
# These are blocked to provide defense-in-depth (primary isolation is via subprocess)
DANGEROUS_PATTERNS = [
    (r'\bos\s*\.\s*(system|popen|spawn|exec|remove|unlink|rmdir|makedirs|chmod|chown)', 
     "OS system/file operations are not allowed"),
    (r'\bsubprocess\b', "subprocess module is not allowed"),
    (r'\b__import__\s*\(', "Dynamic imports via __import__ are not allowed"),
    (r'\beval\s*\(', "eval() is not allowed"),
    (r'\bexec\s*\(', "exec() is not allowed"),
    (r'\bcompile\s*\(', "compile() is not allowed"),
    (r'\bopen\s*\(', "File operations via open() are not allowed"),
    (r'\bglobals\s*\(', "globals() is not allowed"),
    (r'\blocals\s*\(', "locals() is not allowed"),
    (r'\bgetattr\s*\([^,]+,\s*["\']__', "Accessing dunder attributes via getattr is not allowed"),
    (r'\bsetattr\s*\(', "setattr() is not allowed"),
    (r'\bdelattr\s*\(', "delattr() is not allowed"),
    (r'\b__builtins__\b', "Accessing __builtins__ is not allowed"),
    (r'\b__code__\b', "Accessing __code__ is not allowed"),
    (r'\b__globals__\b', "Accessing __globals__ is not allowed"),
    (r'\bsocket\b', "socket module is not allowed"),
    (r'\brequests\b', "requests module is not allowed (use allowed modules only)"),
    (r'\burllib\b', "urllib module is not allowed"),
    (r'\bhttpx\b', "httpx module is not allowed"),
    (r'\baiohttp\b', "aiohttp module is not allowed"),
]

# Allowed import modules (whitelist)
ALLOWED_MODULES = {
    'json', 're', 'math', 'string', 'collections', 'itertools', 'functools',
    'datetime', 'typing', 'dataclasses', 'enum', 'copy', 'hashlib',
}


def _validate_code_safety(code: str) -> None:
    """Validate that user code doesn't contain dangerous patterns.
    
    This provides defense-in-depth. Primary isolation is via subprocess execution.
    
    Args:
        code: The user-provided Python code
        
    Raises:
        InvalidMetricError: If dangerous patterns are detected
    """
    # Check for dangerous patterns
    for pattern, message in DANGEROUS_PATTERNS:
        if re.search(pattern, code):
            logger.warning(f"Code metric rejected: {message}. Pattern: {pattern}")
            raise InvalidMetricError("code", f"Security violation: {message}")
    
    # Validate imports are from allowlist
    import_pattern = r'^\s*(?:from\s+(\w+)|import\s+(\w+))'
    for match in re.finditer(import_pattern, code, re.MULTILINE):
        module = match.group(1) or match.group(2)
        # Allow opik imports and allowed modules
        if module and not module.startswith('opik') and module not in ALLOWED_MODULES:
            logger.warning(f"Code metric rejected: Import of '{module}' is not allowed")
            raise InvalidMetricError(
                "code", 
                f"Import of '{module}' is not allowed. "
                f"Allowed modules: {', '.join(sorted(ALLOWED_MODULES))}, opik.*"
            )
    
    logger.debug("Code safety validation passed")


@MetricFactory.register("code")
def _build_code_metric(params: Dict[str, Any], model: str) -> Callable:
    """Build a custom code metric function.
    
    SECURITY NOTE: User code is executed in an isolated subprocess with:
    - Memory limits (via IsolatedSubprocessExecutor)
    - Timeout limits (configurable)
    - No access to parent process environment secrets
    - Code validation against dangerous patterns
    
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
        InvalidMetricError: If code metrics are disabled, code is missing, 
                           contains dangerous patterns, or is invalid
    """
    from types import ModuleType, FunctionType
    import uuid
    import inspect
    from opik.evaluation.metrics.score_result import ScoreResult
    from opik.evaluation.metrics import BaseMetric
    
    # Check if code metrics are enabled
    if not CODE_METRIC_ENABLED:
        logger.warning("Code metric creation rejected: feature is disabled via OPIK_CODE_METRIC_ENABLED=false")
        raise InvalidMetricError(
            "code", 
            "Custom code metrics are disabled. Contact your administrator to enable this feature."
        )
    
    code = params.get("code")
    if not code:
        raise InvalidMetricError("code", "Missing 'code' parameter for code metric")
    
    # Log code metric creation (for audit purposes, truncate long code)
    code_preview = code[:200] + "..." if len(code) > 200 else code
    logger.info(f"Building code metric. Code preview: {code_preview!r}")
    
    # Validate code safety (defense-in-depth, primary isolation is via subprocess)
    _validate_code_safety(code)
    
    # Create a restricted namespace for code execution
    # Only allow safe builtins
    safe_builtins = {
        'True': True, 'False': False, 'None': None,
        'int': int, 'float': float, 'str': str, 'bool': bool,
        'list': list, 'dict': dict, 'set': set, 'tuple': tuple, 'frozenset': frozenset,
        'len': len, 'range': range, 'enumerate': enumerate, 'zip': zip,
        'map': map, 'filter': filter, 'sorted': sorted, 'reversed': reversed,
        'min': min, 'max': max, 'sum': sum, 'abs': abs, 'round': round,
        'any': any, 'all': all,
        'isinstance': isinstance, 'issubclass': issubclass, 'type': type,
        'hasattr': hasattr, 'getattr': getattr,  # getattr is validated above for dunder access
        'print': print,  # Allow print for debugging
        'Exception': Exception, 'ValueError': ValueError, 'TypeError': TypeError,
        'KeyError': KeyError, 'IndexError': IndexError, 'AttributeError': AttributeError,
        '__import__': None,  # Explicitly disable __import__
    }
    
    # Create a module to execute the code
    module = ModuleType(str(uuid.uuid4()))
    module.__dict__['__builtins__'] = safe_builtins
    
    # Pre-import commonly used modules in the metric code's namespace
    module.__dict__["json"] = __import__("json")
    module.__dict__["re"] = __import__("re")
    module.__dict__["math"] = __import__("math")
    module.__dict__["ScoreResult"] = ScoreResult
    module.__dict__["BaseMetric"] = BaseMetric
    
    # Track what was in the namespace before exec
    pre_exec_names = set(module.__dict__.keys())
    
    try:
        # Execute with restricted globals
        exec(code, module.__dict__)
        logger.info("Code metric compiled successfully")
    except Exception as e:
        logger.warning(f"Code metric compilation failed: {e}")
        raise InvalidMetricError("code", f"Invalid Python code: {e}")
    
    # Find the metric - look for either a function or a BaseMetric class
    # These are new names that appeared after exec
    new_names = set(module.__dict__.keys()) - pre_exec_names
    
    metric_fn = None
    metric_class = None
    
    for name in new_names:
        obj = module.__dict__[name]
        
        # Check for BaseMetric subclass (class pattern - same as automations)
        if inspect.isclass(obj) and issubclass(obj, BaseMetric) and obj is not BaseMetric:
            metric_class = obj
            break
        
        # Check for function pattern
        if isinstance(obj, FunctionType) and not name.startswith("_"):
            # Check function signature - should accept at least 2 arguments
            try:
                sig = inspect.signature(obj)
                params_list = list(sig.parameters.keys())
                if len(params_list) >= 2:
                    metric_fn = obj
                    # Don't break - prefer class pattern if both exist
            except (ValueError, TypeError):
                # Can't inspect signature, skip this function
                continue
    
    # If we found a BaseMetric class, wrap it to match the optimizer signature
    if metric_class is not None:
        metric_instance = metric_class()
        logger.info(f"Created code metric from BaseMetric class: {metric_class.__name__}")
        
        def wrapped_class_metric(dataset_item, llm_output):
            # BaseMetric.score expects output as first arg and kwargs for the rest
            return metric_instance.score(output=llm_output, **dataset_item)
        
        wrapped_class_metric.__name__ = "code_metric"
        return wrapped_class_metric
    
    # If we found a function, wrap it
    if metric_fn is not None:
        original_fn = metric_fn
        logger.info(f"Created code metric from function: {metric_fn.__name__}")
        
        def wrapped_fn_metric(dataset_item, llm_output):
            return original_fn(dataset_item, llm_output)
        
        wrapped_fn_metric.__name__ = "code_metric"
        return wrapped_fn_metric
    
    raise InvalidMetricError(
        "code", 
        "Code must define either:\n"
        "1. A function: def my_metric(dataset_item, llm_output) -> ScoreResult\n"
        "2. A class: class MyMetric(BaseMetric) with a score() method"
    )

