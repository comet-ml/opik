import logging
import re
from collections.abc import Mapping
from typing import Any, Dict, Literal, Optional, cast

from opik import _logging

LOGGER = logging.getLogger(__name__)

SpanType = Literal["llm", "tool", "general"]


def get_span_type(run: Dict[str, Any]) -> SpanType:
    if run.get("run_type") in ["llm", "tool"]:
        return cast(SpanType, run.get("run_type"))

    if run.get("run_type") in ["prompt"]:
        return cast(SpanType, "tool")

    return cast(SpanType, "general")


def is_root_run(run_dict: Dict[str, Any]) -> bool:
    return run_dict.get("parent_run_id") is None


def extract_tool_description(run_dict: Dict[str, Any]) -> Optional[str]:
    """
    Extract tool description/docstring from a tool Run object.

    The function looks for the description in the following locations (in order):
    1. serialized["kwargs"]["description"] - explicit description attribute
    2. serialized["description"] - top-level description in serialized data

    Args:
        run_dict: Dictionary representation of a LangChain Run object.

    Returns:
        The tool description as a string if found, None otherwise.
    """
    serialized = run_dict.get("serialized")
    if not isinstance(serialized, dict):
        return None

    # Try to get description from kwargs (most common location for @tool decorated functions)
    kwargs = serialized.get("kwargs", {})
    if isinstance(kwargs, dict):
        description = kwargs.get("description")
        if description and isinstance(description, str):
            return description

    # Try top-level description in serialized data
    description = serialized.get("description")
    if description and isinstance(description, str):
        return description

    return None


def get_run_metadata(run_dict: Dict[str, Any]) -> Dict[str, Any]:
    extra = run_dict.get("extra") or {}
    if not isinstance(extra, dict):
        extra = {}
    metadata = extra.get("metadata", {}).copy()

    # Extract tool description for tool runs and add to metadata
    if run_dict.get("run_type") == "tool":
        tool_description = extract_tool_description(run_dict)
        if tool_description:
            metadata["tool_description"] = tool_description

    return metadata


def parse_graph_interrupt_value(error_traceback: str) -> Optional[str]:
    """
    Parse GraphInterrupt error traceback to extract the interrupt value as a string.

    The function extracts the value from the Interrupt object representation in the traceback.
    It handles both string values (with quotes) and non-string values, including nested structures.
    For string values, escape sequences are decoded (e.g., \\n becomes a newline character).

    Args:
        error_traceback: The error traceback string containing GraphInterrupt information.

    Returns:
        The interrupt value as a string if found, None otherwise.
    """
    # Search for GraphInterrupt( or NodeInterrupt( anywhere in the traceback.
    # NodeInterrupt is deprecated but still exists as a subclass of GraphInterrupt.
    match = re.search(
        r"(?:GraphInterrupt|NodeInterrupt)\(.*?Interrupt\(value=",
        error_traceback,
        re.DOTALL,
    )
    if not match:
        return None

    # Start parsing from after "value="
    start_pos = match.end()
    value_str = error_traceback[start_pos:]

    # Extract the value, handling nested parentheses and brackets
    paren_depth = 0
    bracket_depth = 0
    brace_depth = 0
    in_string = False
    string_char = None
    i = 0

    for i, char in enumerate(value_str):
        # Handle string boundaries
        if char in ('"', "'") and (i == 0 or value_str[i - 1] != "\\"):
            if not in_string:
                in_string = True
                string_char = char
            elif char == string_char:
                in_string = False
                string_char = None

        # Skip counting brackets/parens inside strings
        if in_string:
            continue

        # Track nesting depth
        if char == "(":
            paren_depth += 1
        elif char == ")":
            if paren_depth > 0:
                paren_depth -= 1
            else:
                # Found the closing paren of Interrupt(...), stop here
                break
        elif char == "[":
            bracket_depth += 1
        elif char == "]":
            bracket_depth -= 1
        elif char == "{":
            brace_depth += 1
        elif char == "}":
            brace_depth -= 1
        elif (
            char == "," and paren_depth == 0 and bracket_depth == 0 and brace_depth == 0
        ):
            # Found a comma at the top level, stop here
            break

    # Extract and clean the value
    value = value_str[:i].strip()

    # Check if the value was originally a quoted string
    was_quoted_string = False
    if len(value) >= 2 and value[0] in ('"', "'") and value[-1] == value[0]:
        was_quoted_string = True
        value = value[1:-1]

    # Decode escape sequences for string values
    if was_quoted_string:
        try:
            value = value.encode("utf-8").decode("unicode_escape")
        except (UnicodeDecodeError, AttributeError):
            # If decoding fails, return the original value
            pass

    return value


def is_langgraph_parent_command(error_traceback: str) -> bool:
    """
    Check if the error traceback represents a LangGraph ParentCommand exception.

    ParentCommand is raised internally by LangGraph when a subgraph node needs to
    route execution to a node in the parent graph. This is a control flow mechanism
    used in multi-agent/supervisor patterns, not a real error.

    Args:
        error_traceback: The error traceback string.

    Returns:
        True if the error is a ParentCommand exception, False otherwise.
    """
    return bool(
        re.search(r"langgraph\.errors\.ParentCommand", error_traceback)
        or error_traceback.startswith("ParentCommand(")
    )


def extract_resume_value_from_command(obj: Any) -> Optional[str]:
    """
    Extract the resume value from a LangGraph Command object or serialized Command dict.

    Args:
        obj: A Command object or dict representing a serialized Command object (from run.dict()).

    Returns:
        The resume value as a string if found, None otherwise.
    """
    # Check if it's a Command object (has a resume attribute)
    if hasattr(obj, "resume") and obj.resume is not None:
        return str(obj.resume)
    # Check if it's a serialized Command dict
    if obj is not None and isinstance(obj, dict) and "resume" in obj:
        return str(obj["resume"])
    return None


def extract_command_update(outputs: Dict[str, Any]) -> Dict[str, Any]:
    """Extract state updates from LangGraph Command objects.

    When a LangGraph node returns a Command, LangChain wraps it in {"output": Command(...)}.
    This function detects Command objects and extracts the update dict to properly log state changes.

    Args:
        outputs: The outputs dict from a LangChain Run.

    Returns:
        The extracted update dict if a Command is found, otherwise the original outputs.
    """
    if "output" in outputs and len(outputs) == 1:
        output_value = outputs["output"]
        # Duck-type check for Command object
        if hasattr(output_value, "update") and hasattr(output_value, "goto"):
            try:
                update_value = output_value.update
                # Handle None - skip extraction
                if update_value is None:
                    return outputs
                # Accept any Mapping type and convert to dict for JSON serialization
                if isinstance(update_value, Mapping):
                    _logging.log_once_at_level(
                        logging.DEBUG,
                        "Extracted state update from LangGraph Command object",
                        LOGGER,
                    )
                    return dict(update_value)
            except Exception as e:
                LOGGER.warning(
                    f"Failed to extract update from Command-like object: {e}",
                    exc_info=True,
                )

    return outputs
