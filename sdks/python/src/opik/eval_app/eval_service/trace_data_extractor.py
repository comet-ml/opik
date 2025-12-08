"""Trace data extraction using JSONPath for field mapping."""

import logging
from typing import Any, Dict

from opik.rest_api.types import trace_public

LOGGER = logging.getLogger(__name__)


def trace_to_dict(trace: trace_public.TracePublic) -> Dict[str, Any]:
    """Convert a trace object to a dictionary for JSONPath querying.

    Args:
        trace: The trace object to convert.

    Returns:
        A dictionary representation of the trace with input, output, and metadata fields.
    """
    result: Dict[str, Any] = {}

    if hasattr(trace, "input") and trace.input is not None:
        result["input"] = (
            trace.input if isinstance(trace.input, dict) else {"value": trace.input}
        )

    if hasattr(trace, "output") and trace.output is not None:
        result["output"] = (
            trace.output if isinstance(trace.output, dict) else {"value": trace.output}
        )

    if hasattr(trace, "metadata") and trace.metadata is not None:
        result["metadata"] = trace.metadata

    return result


def extract_field_value(
    trace_dict: Dict[str, Any],
    field_path: str,
) -> Any:
    """Extract a value from a trace dictionary using JSONPath-like syntax.

    Supports paths like:
    - input
    - input.messages
    - input.messages[0]
    - input.messages[0].content
    - input.messages[*].content (all content fields)

    Args:
        trace_dict: The trace data as a dictionary.
        field_path: The JSONPath-like field path to extract.

    Returns:
        The extracted value, or None if not found.
        Returns a list if multiple matches (e.g., wildcard).
    """
    from jsonpath_ng import parse as jsonpath_parse
    from jsonpath_ng import exceptions as jsonpath_exceptions

    # Handle special dataset_item_data prefix
    if field_path.startswith("dataset_item_data."):
        field_path = "metadata." + field_path

    # Convert field path to JSONPath format (add $. prefix if not present)
    jsonpath_expr = field_path if field_path.startswith("$") else f"$.{field_path}"

    try:
        jsonpath = jsonpath_parse(jsonpath_expr)
        matches = jsonpath.find(trace_dict)

        if not matches:
            LOGGER.debug(
                "JSONPath '%s' found no matches. Available top-level keys: %s",
                field_path,
                list(trace_dict.keys()),
            )
            return None

        # Return single value if one match, list if multiple
        if len(matches) == 1:
            return matches[0].value
        return [match.value for match in matches]

    except jsonpath_exceptions.JsonPathParserError as e:
        LOGGER.error("Invalid JSONPath expression '%s': %s", field_path, e)
        return None


def build_metric_inputs(
    trace_dict: Dict[str, Any],
    arguments_mapping: Dict[str, str],
) -> Dict[str, Any]:
    """Build metric inputs by applying field mappings to trace data.

    Args:
        trace_dict: The trace data as a dictionary.
        arguments_mapping: Mapping from metric argument names to trace field paths.
            Example: {"input": "input.messages[0].content", "output": "output.output"}

    Returns:
        Dictionary of metric inputs with argument names as keys.
    """
    metric_inputs: Dict[str, Any] = {}

    for arg_name, field_path in arguments_mapping.items():
        value = extract_field_value(trace_dict, field_path)
        if value is None:
            LOGGER.warning(
                "Field '%s' not found in trace or is None, "
                "metric argument '%s' will be None",
                field_path,
                arg_name,
            )
        metric_inputs[arg_name] = value

    return metric_inputs


def extract_metric_inputs_from_trace(
    trace: trace_public.TracePublic,
    arguments_mapping: Dict[str, str],
) -> Dict[str, Any]:
    """Convenience function to extract metric inputs directly from a trace object.

    Args:
        trace: The trace object.
        arguments_mapping: Mapping from metric argument names to trace field paths.

    Returns:
        Dictionary of metric inputs with argument names as keys.
    """
    trace_dict = trace_to_dict(trace)
    return build_metric_inputs(trace_dict, arguments_mapping)

