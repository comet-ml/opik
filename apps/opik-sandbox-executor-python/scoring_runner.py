import inspect
import json
import traceback
import uuid
from sys import argv
from types import ModuleType
from typing import Type, Union, List, Any, Dict, Optional

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

# Constants
TRACE_THREAD_METRIC_TYPE = "trace_thread"  # Referenced in the payload_types.py as it's not available in the scoring_commands.py process
COMMON_METRIC_TYPE = "common_metric"  # For common heuristic metrics from the SDK

def get_metric_class(module: ModuleType) -> Type[BaseMetric]:
    for _, cls in inspect.getmembers(module, inspect.isclass):
        if issubclass(cls, BaseMetric) and cls != BaseMetric:
            return cls
    return None


def to_scores(score_result: Union[ScoreResult, List[ScoreResult]]) -> List[ScoreResult]:
    scores = []
    if score_result is None:
        return scores
    if isinstance(score_result, ScoreResult):
        scores = [score_result]
    elif isinstance(score_result, list):
        for item in score_result:
            if isinstance(item, ScoreResult):
                scores.append(item)
    return scores


def _format_metric_error(exc: Exception, error_prefix: str) -> dict:
    """
    Format an exception into a standardized error dictionary.
    
    Args:
        exc: The exception to format
        error_prefix: Prefix message for the error (e.g., "Failed to execute metric")
    
    Returns:
        Dictionary with 'error' key containing formatted error message
    """
    # Get full traceback, skip first 3 lines (Traceback header)
    tb_lines = traceback.format_exc().splitlines()
    stacktrace = "\\n".join(tb_lines[3:]) if len(tb_lines) > 3 else str(exc)
    # Ensure we always have an error message
    error_msg = stacktrace if stacktrace.strip() else str(exc)
    return {"error": f"{error_prefix}: {error_msg}"}


def find_metric_class(metric_id: str) -> Optional[Type[BaseMetric]]:
    """
    Find a common metric class by its snake_case ID.
    
    This function mirrors the common_metrics.find_metric_class but is
    implemented here to avoid importing the entire common_metrics module
    which may not be available in the Docker sandbox.
    
    Args:
        metric_id: The snake_case ID of the metric (e.g., 'contains', 'equals')
    
    Returns:
        The metric class if found, None otherwise
    """
    try:
        from opik.evaluation.metrics import heuristics
        import re
        
        def camel_to_snake(name: str) -> str:
            """Convert CamelCase to snake_case."""
            s1 = re.sub("(.)([A-Z][a-z]+)", r"\1_\2", name)
            return re.sub("([a-z0-9])([A-Z])", r"\1_\2", s1).lower()
        
        # Scan heuristics module for the metric
        for name in dir(heuristics):
            obj = getattr(heuristics, name)
            
            if not inspect.ismodule(obj):
                continue
            
            try:
                for cls_name in dir(obj):
                    cls = getattr(obj, cls_name)
                    
                    if not inspect.isclass(cls):
                        continue
                    
                    # Check if this class matches the requested metric_id
                    if camel_to_snake(cls_name) == metric_id:
                        return cls
            except Exception:
                # Skip modules that fail to load
                continue
        
        return None
    except Exception:
        # If we can't import heuristics at all, return None
        return None


def run_common_metric(data: Dict[str, Any]) -> dict:
    """
    Run a common heuristic metric from the opik SDK.
    
    Args:
        data: Dictionary containing metric_id, init_config, and scoring_kwargs
    
    Returns:
        Dictionary with 'scores' list on success, or 'error' on failure
    """
    # Validate input data
    metric_id = data.get('metric_id')
    init_config = data.get('init_config', {})
    scoring_kwargs = data.get('scoring_kwargs', {})
    
    if not metric_id:
        return {"error": "Field 'metric_id' is missing in data for common_metric type"}
    
    if not isinstance(scoring_kwargs, dict):
        return {"error": "Field 'scoring_kwargs' must be a dictionary"}
    
    if not isinstance(init_config, dict):
        return {"error": "Field 'init_config' must be a dictionary"}
    
    try:
        # Find the metric class
        metric_cls = find_metric_class(metric_id)
        if metric_cls is None:
            return {"error": f"Unknown metric: {metric_id}"}
        
        # Instantiate the metric
        metric = metric_cls(**{**init_config, "track": False})
        
        # Call the score method
        result = metric.score(**scoring_kwargs)
        
        # Convert to list of scores
        scores = to_scores(result)
        
        return {"scores": [score.__dict__ for score in scores]}
    
    except Exception as e:
        return _format_metric_error(e, "Failed to execute metric")


def run_user_metric(
    code: str,
    data: Dict[str, Any],
    payload_type: Optional[str],
) -> dict:
    """
    Run a user-provided metric from custom code.
    
    Args:
        code: Python code containing a BaseMetric subclass
        data: Data to pass to the metric's score method
        payload_type: Type of payload (e.g., 'trace_thread' for special handling)
    
    Returns:
        Dictionary with 'scores' list on success, or 'error' on failure
    """
    # Create a module to execute the code
    module = ModuleType(str(uuid.uuid4()))
    
    try:
        exec(code, module.__dict__)
    except Exception:
        stacktrace = "\\n".join(traceback.format_exc().splitlines()[3:])
        return {"error": f"Field 'code' contains invalid Python code: {stacktrace}"}
    
    # Find the metric class in the executed module
    metric_class = get_metric_class(module)
    if metric_class is None:
        return {"error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}
    
    # Execute the metric
    try:
        metric = metric_class()
        
        # Handle trace_thread type differently - pass data as first positional argument
        if payload_type == TRACE_THREAD_METRIC_TYPE:
            score_result = metric.score(data)
        else:
            # Regular scoring - unpack data as keyword arguments
            score_result = metric.score(**data)
        
        # Convert to list of scores
        scores = to_scores(score_result)
        
        return {"scores": [score.__dict__ for score in scores]}
    
    except Exception as e:
        return _format_metric_error(e, "The provided 'code' and 'data' fields can't be evaluated")


def main():
    """Main entry point for the scoring runner."""
    code = argv[1]
    data = json.loads(argv[2])
    payload_type = argv[3] if len(argv) > 3 else None
    
    # Route to appropriate handler based on payload type
    if payload_type == COMMON_METRIC_TYPE:
        result = run_common_metric(data)
    else:
        result = run_user_metric(code, data, payload_type)
    
    # Output result and exit
    print(json.dumps(result))
    exit(0 if "error" not in result else 1)


if __name__ == "__main__":
    main()
