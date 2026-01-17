"""Utility functions and constants for the optimizer package."""

from typing import Any, Final, TYPE_CHECKING

import ast
import base64
import json
import logging
import os
import random
import urllib.parse


import opik
import opik.config


if TYPE_CHECKING:
    pass

ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="
logger = logging.getLogger(__name__)


def get_random_seed() -> int:
    """
    Get a random seed for reproducibility.

    Returns:
        int: A random seed
    """

    return random.randint(0, 2**32 - 1)


def json_to_dict(json_str: str) -> Any:
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
                logger.debug("Failed to parse JSON string: %s", json_str)
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


def ensure_ending_slash(url: str) -> str:
    return url.rstrip("/") + "/"


def get_optimization_run_url_by_id(
    dataset_id: str | None, optimization_id: str | None
) -> str:
    if dataset_id is None or optimization_id is None:
        raise ValueError(
            "Cannot create a new run link without a dataset_id and optimization_id."
        )

    opik_config = opik.config.get_from_user_inputs()
    url_override = opik_config.url_override
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    run_path = urllib.parse.quote(
        f"v1/session/redirect/optimizations/?optimization_id={optimization_id}&dataset_id={dataset_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ensure_ending_slash(url_override), run_path)


# Deprecated functions
def __getattr__(name: str) -> Any:
    """Provide backward compatibility for moved Wikipedia functions."""
    if name == "search_wikipedia":
        import warnings
        from .tools.wikipedia import search_wikipedia

        warnings.warn(
            "Importing search_wikipedia from opik_optimizer.utils.core is deprecated. "
            "Use: from opik_optimizer.utils.tools.wikipedia import search_wikipedia",
            DeprecationWarning,
            stacklevel=2,
        )
        return search_wikipedia
    elif name == "_search_wikipedia_api":
        import warnings
        from .tools.wikipedia import _search_wikipedia_api

        warnings.warn(
            "_search_wikipedia_api is internal and has moved. "
            "Use: from opik_optimizer.utils.tools.wikipedia import search_wikipedia",
            DeprecationWarning,
            stacklevel=2,
        )
        return _search_wikipedia_api
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
