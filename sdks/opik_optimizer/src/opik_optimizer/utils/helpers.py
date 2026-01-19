"""Helper utility functions for JSON parsing and data conversion."""

from typing import Any

import ast
import json
import logging

logger = logging.getLogger(__name__)


def json_to_dict(json_str: str) -> Any:
    """
    Parse a JSON string, handling code blocks and Python literals.

    Attempts to parse JSON, falling back to Python literal evaluation if needed.
    Handles code blocks (```json and ```) and converts Python literals
    (tuples, sets) to JSON-compatible structures.

    Args:
        json_str: String to parse as JSON

    Returns:
        Parsed dictionary/list/value, or None if completely unparsable
    """
    cleaned_json_string = json_str.strip()

    try:
        return json.loads(cleaned_json_string)
    except json.JSONDecodeError as json_error:
        if cleaned_json_string.startswith("```json"):
            cleaned_json_string = cleaned_json_string[7:]
            if cleaned_json_string.endswith("```"):
                cleaned_json_string = cleaned_json_string[:-3]
        elif cleaned_json_string.startswith("```"):
            cleaned_json_string = cleaned_json_string[3:]
            if cleaned_json_string.endswith("```"):
                cleaned_json_string = cleaned_json_string[:-3]

        try:
            return json.loads(cleaned_json_string)
        except json.JSONDecodeError:
            try:
                literal_result = ast.literal_eval(cleaned_json_string)
            except (ValueError, SyntaxError):
                raise json_error

            normalized = _convert_literals_to_json_compatible(literal_result)

            try:
                return json.loads(json.dumps(normalized))
            except (TypeError, ValueError) as serialization_error:
                logger.debug(
                    "Failed to serialise literal-evaluated payload %r: %s",
                    literal_result,
                    serialization_error,
                )
                raise json_error
        except Exception as exc:
            # As a last resort, return None so callers can fallback instead of crashing.
            logger.debug("Returning None for unparsable JSON payload: %s", exc)
            return None


def _convert_literals_to_json_compatible(value: Any) -> Any:
    """Convert Python literals to JSON-compatible structures."""
    if isinstance(value, dict):
        return {
            key: _convert_literals_to_json_compatible(val) for key, val in value.items()
        }
    if isinstance(value, list):
        return [_convert_literals_to_json_compatible(item) for item in value]
    if isinstance(value, tuple):
        return [_convert_literals_to_json_compatible(item) for item in value]
    if isinstance(value, set):
        return [
            _convert_literals_to_json_compatible(item)
            for item in sorted(value, key=repr)
        ]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)
