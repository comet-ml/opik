"""
Common metrics module for exposing Python SDK heuristic metrics.

This module provides endpoints to:
1. List available common metrics with their metadata
2. Execute metrics by name with provided parameters

The metrics are dynamically discovered from the opik.evaluation.metrics.heuristics
module, excluding only those that require heavy ML models (like BERTScore).
"""

import inspect
import logging
import re
from typing import Any, Dict, List, Optional, Set, Type, get_type_hints

from flask import Blueprint, jsonify, abort, request, current_app

from opik.evaluation.metrics import base_metric

common_metrics_bp = Blueprint(
    "common_metrics", __name__, url_prefix="/v1/private/evaluators"
)

# Metrics to exclude from the common metrics registry
# These require heavy ML models or special dependencies not available in the container
EXCLUDED_METRICS: Set[str] = {
    "BERTScore",  # Requires bert-score package + PyTorch + transformer models
    "LanguageAdherenceMetric",  # Requires fasttext which has C++ compilation issues
    "BaseBLEU",  # Base class for BLEU metrics
}

# Parameters that should be excluded from the user-configurable init parameters
# These are internal parameters handled by the system
EXCLUDED_INIT_PARAMS = {"self", "name", "track", "project_name"}

# Parameters that should be excluded from the score method parameters
# These are internal parameters
EXCLUDED_SCORE_PARAMS = {"self", "ignored_kwargs"}

# Parameters that can be mapped to trace/span fields in the UI
# These are the standard field names that the system can auto-populate
MAPPABLE_PARAMS = {"output", "input", "context"}

# Module-level logger for use outside Flask app context
_logger = logging.getLogger(__name__)

# Cached registry of metrics - populated at module load time
_COMMON_METRICS_REGISTRY: Optional[Dict[str, Type[base_metric.BaseMetric]]] = None


def _camel_to_snake(name: str) -> str:
    """Convert CamelCase to snake_case."""
    # Insert underscore before uppercase letters and convert to lowercase
    s1 = re.sub("(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub("([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def _discover_heuristic_metrics() -> Dict[str, Type[base_metric.BaseMetric]]:
    """
    Dynamically discover all heuristic metrics from the opik SDK.

    Returns:
        Dictionary mapping metric IDs (snake_case) to metric classes.
    """
    from opik.evaluation.metrics import heuristics

    metrics: Dict[str, Type[base_metric.BaseMetric]] = {}

    # Get all modules in the heuristics package
    for name in dir(heuristics):
        obj = getattr(heuristics, name)

        # Skip non-modules
        if not inspect.ismodule(obj):
            continue

        # Scan module for BaseMetric subclasses
        for cls_name in dir(obj):
            cls = getattr(obj, cls_name)

            # Check if it's a class and a subclass of BaseMetric
            if not inspect.isclass(cls):
                continue

            if not issubclass(cls, base_metric.BaseMetric):
                continue

            # Skip the base class itself
            if cls is base_metric.BaseMetric:
                continue

            # Skip excluded metrics
            if cls_name in EXCLUDED_METRICS:
                _logger.debug(f"Skipping excluded metric: {cls_name}")
                continue

            # Skip private/internal classes
            if cls_name.startswith("_"):
                continue

            # Generate metric ID from class name
            metric_id = _camel_to_snake(cls_name)

            # Add to registry (avoid duplicates)
            if metric_id not in metrics:
                metrics[metric_id] = cls
                _logger.debug(f"Discovered metric: {cls_name} -> {metric_id}")

    return metrics


def _get_common_metrics_registry() -> Dict[str, Type[base_metric.BaseMetric]]:
    """Get the cached metrics registry, discovering metrics if needed."""
    global _COMMON_METRICS_REGISTRY

    if _COMMON_METRICS_REGISTRY is None:
        _logger.info("Discovering heuristic metrics from SDK...")
        _COMMON_METRICS_REGISTRY = _discover_heuristic_metrics()
        _logger.info(f"Discovered {len(_COMMON_METRICS_REGISTRY)} heuristic metrics")

    return _COMMON_METRICS_REGISTRY


def _get_class_docstring(cls: Type) -> str:
    """Extract the main description from a class docstring."""
    if not cls.__doc__:
        return ""

    # Get the first paragraph (up to Args: section)
    lines = cls.__doc__.strip().split("\n")
    description_lines = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("Args:") or stripped.startswith("Example"):
            break
        description_lines.append(stripped)

    # Join and clean up
    description = " ".join(description_lines)
    # Remove extra whitespace
    description = " ".join(description.split())
    return description


def _parse_args_from_docstring(docstring: str) -> Dict[str, str]:
    """Parse the Args section from a docstring to extract parameter descriptions."""
    if not docstring:
        return {}

    descriptions = {}
    in_args_section = False
    current_param = None
    current_desc_lines: List[str] = []

    for line in docstring.split("\n"):
        stripped = line.strip()

        # Check if we're entering the Args section
        if stripped.startswith("Args:"):
            in_args_section = True
            continue

        # Check if we're leaving the Args section
        if (
            in_args_section
            and stripped
            and not stripped[0].isspace()
            and ":" not in stripped[:20]
        ):
            if stripped.startswith(("Returns:", "Raises:", "Example", "Note:")):
                # Save the last parameter if any
                if current_param:
                    descriptions[current_param] = " ".join(current_desc_lines).strip()
                break

        if in_args_section:
            # Check if this is a new parameter definition (param_name: description)
            if ":" in stripped and not stripped.startswith(" "):
                # Save previous parameter
                if current_param:
                    descriptions[current_param] = " ".join(current_desc_lines).strip()

                # Parse new parameter
                parts = stripped.split(":", 1)
                current_param = parts[0].strip()
                current_desc_lines = [parts[1].strip()] if len(parts) > 1 else []
            elif current_param and stripped:
                # Continuation of previous parameter description
                current_desc_lines.append(stripped)

    # Don't forget the last parameter
    if current_param:
        descriptions[current_param] = " ".join(current_desc_lines).strip()

    return descriptions


def _get_type_string(annotation: Any) -> str:
    """Convert a type annotation to a string representation."""
    if annotation is inspect.Parameter.empty:
        return "any"

    # Handle Optional types
    origin = getattr(annotation, "__origin__", None)
    if origin is not None:
        # Handle Optional[X] which is Union[X, None]
        args = getattr(annotation, "__args__", ())
        if type(None) in args:
            # It's an Optional type
            non_none_args = [a for a in args if a is not type(None)]
            if len(non_none_args) == 1:
                return _get_type_string(non_none_args[0])

        # Handle other generic types like List, Dict, etc.
        type_name = getattr(origin, "__name__", str(origin))
        return type_name.lower()

    # Handle basic types
    if hasattr(annotation, "__name__"):
        return annotation.__name__.lower()

    return str(annotation).lower()


def _extract_init_parameters(cls: Type[base_metric.BaseMetric]) -> List[Dict[str, Any]]:
    """Extract __init__ parameters from a metric class."""
    parameters = []

    try:
        sig = inspect.signature(cls.__init__)
        # Try to get type hints, but don't fail if unavailable
        try:
            hints = get_type_hints(cls.__init__)
        except Exception:
            hints = {}

        # Get parameter descriptions from docstring
        docstring = cls.__doc__ or ""
        param_descriptions = _parse_args_from_docstring(docstring)

        for param_name, param in sig.parameters.items():
            if param_name in EXCLUDED_INIT_PARAMS:
                continue

            param_type = _get_type_string(hints.get(param_name, param.annotation))
            has_default = param.default is not inspect.Parameter.empty
            default_value = param.default if has_default else None

            # Convert default value to string for JSON serialization
            if default_value is None:
                default_str = None
            elif isinstance(default_value, bool):
                default_str = str(default_value)
            else:
                default_str = str(default_value) if default_value is not None else None

            parameters.append(
                {
                    "name": param_name,
                    "type": param_type,
                    "description": param_descriptions.get(param_name, ""),
                    "default_value": default_str,
                    "required": not has_default,
                }
            )
    except Exception as e:
        _logger.warning(f"Failed to extract init parameters for {cls.__name__}: {e}")

    return parameters


def _extract_score_parameters(cls: Type[base_metric.BaseMetric]) -> List[Dict[str, Any]]:
    """
    Extract score method parameters from a metric class.

    Includes the 'mappable' field indicating whether the parameter can be
    automatically mapped to trace/span fields in the UI.
    """
    parameters = []

    try:
        # Get the score method
        score_method = getattr(cls, "score", None)
        if score_method is None:
            return parameters

        sig = inspect.signature(score_method)
        # Try to get type hints
        try:
            hints = get_type_hints(score_method)
        except Exception:
            hints = {}

        # Get parameter descriptions from score method docstring
        score_docstring = score_method.__doc__ or ""
        param_descriptions = _parse_args_from_docstring(score_docstring)

        for param_name, param in sig.parameters.items():
            if param_name in EXCLUDED_SCORE_PARAMS:
                continue

            # Skip **kwargs style parameters
            if param.kind == inspect.Parameter.VAR_KEYWORD:
                continue

            param_type = _get_type_string(hints.get(param_name, param.annotation))
            has_default = param.default is not inspect.Parameter.empty

            # Determine if this parameter is mappable to trace/span fields
            # 'output' and 'input' are standard fields that can be auto-populated
            is_mappable = param_name in MAPPABLE_PARAMS

            parameters.append(
                {
                    "name": param_name,
                    "type": param_type,
                    "description": param_descriptions.get(param_name, ""),
                    "required": not has_default,
                    "mappable": is_mappable,
                }
            )
    except Exception as e:
        _logger.warning(f"Failed to extract score parameters for {cls.__name__}: {e}")

    return parameters


def get_common_metrics_list() -> List[Dict[str, Any]]:
    """Get the list of all available common metrics with their metadata.

    When a parameter appears in both __init__ and score methods (like 'reference'),
    it is only exposed as an init parameter to avoid duplication. The init parameter
    provides the default value, while the score method allows runtime override.
    """
    metrics = []
    registry = _get_common_metrics_registry()

    for metric_id, metric_cls in sorted(registry.items()):
        try:
            init_params = _extract_init_parameters(metric_cls)
            score_params = _extract_score_parameters(metric_cls)

            # Get the names of init parameters to filter out duplicates from score params
            init_param_names = {p["name"] for p in init_params}

            # Remove score parameters that are already in init parameters
            # These parameters can be set at construction time and optionally overridden
            deduplicated_score_params = [
                p for p in score_params if p["name"] not in init_param_names
            ]

            metric_info = {
                "id": metric_id,
                "name": metric_cls.__name__,
                "description": _get_class_docstring(metric_cls),
                "init_parameters": init_params,
                "score_parameters": deduplicated_score_params,
            }
            metrics.append(metric_info)
        except Exception as e:
            _logger.warning(f"Failed to extract metadata for metric {metric_id}: {e}")

    return metrics


def instantiate_metric(
    metric_id: str, init_config: Optional[Dict[str, Any]] = None
) -> base_metric.BaseMetric:
    """
    Instantiate a metric by its ID with the given configuration.

    Args:
        metric_id: The ID of the metric to instantiate
        init_config: Optional dictionary of __init__ parameters

    Returns:
        An instance of the metric

    Raises:
        ValueError: If the metric ID is not found
    """
    registry = _get_common_metrics_registry()

    if metric_id not in registry:
        raise ValueError(f"Unknown metric: {metric_id}")

    metric_cls = registry[metric_id]

    # Prepare init kwargs
    init_kwargs = {}
    if init_config:
        for key, value in init_config.items():
            # Skip excluded parameters
            if key in EXCLUDED_INIT_PARAMS:
                continue
            init_kwargs[key] = value

    # Always disable tracking for online evaluation (handled separately)
    init_kwargs["track"] = False

    return metric_cls(**init_kwargs)


@common_metrics_bp.route("/common-metrics", methods=["GET"])
def list_common_metrics():
    """List all available common metrics with their metadata."""
    try:
        metrics = get_common_metrics_list()
        return jsonify({"content": metrics})
    except Exception as e:
        current_app.logger.exception("Failed to list common metrics")
        abort(500, f"Failed to list common metrics: {str(e)}")


@common_metrics_bp.route("/common-metrics/<metric_id>/score", methods=["POST"])
def execute_common_metric(metric_id: str):
    """
    Execute a common metric by its ID.

    Expected payload:
    {
        "init_config": { ... },  // Optional: __init__ parameters
        "data": { ... }          // Required: score method parameters
    }
    """
    registry = _get_common_metrics_registry()

    if metric_id not in registry:
        abort(404, f"Unknown metric: {metric_id}")

    payload = request.get_json(force=True)

    init_config = payload.get("init_config", {})
    data = payload.get("data")

    if data is None:
        abort(400, "Field 'data' is missing in the request")

    try:
        # Instantiate the metric
        metric = instantiate_metric(metric_id, init_config)

        # Call the score method
        result = metric.score(**data)

        # Convert result to dict
        if hasattr(result, "__iter__") and not isinstance(result, dict):
            # Multiple results
            scores = [
                {"name": r.name, "value": r.value, "reason": getattr(r, "reason", None)}
                for r in result
            ]
        else:
            # Single result
            scores = [
                {
                    "name": result.name,
                    "value": result.value,
                    "reason": getattr(result, "reason", None),
                }
            ]

        return jsonify({"scores": scores})

    except ValueError as e:
        abort(400, str(e))
    except Exception as e:
        current_app.logger.exception(f"Failed to execute metric {metric_id}")
        abort(500, f"Failed to execute metric: {str(e)}")
