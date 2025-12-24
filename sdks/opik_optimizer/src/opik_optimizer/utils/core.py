"""Utility functions and constants for the optimizer package."""

from typing import Any, Final, Literal, TYPE_CHECKING
from collections.abc import Callable

import ast
import inspect
import base64
import json
import logging
import os
import random
import string
import urllib.parse
from types import TracebackType


import opik
import opik.config
from opik.api_objects.opik_client import Opik
from opik.api_objects.optimization import Optimization


if TYPE_CHECKING:
    pass

ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="
logger = logging.getLogger(__name__)
_DEFAULT_LOG_LEVEL = os.environ.get("OPIK_OPTIMIZER_LOG_LEVEL", "WARNING").upper()
numeric_level = logging.getLevelName(_DEFAULT_LOG_LEVEL)
if isinstance(numeric_level, int):
    logger.setLevel(numeric_level)


class OptimizationContextManager:
    """
    Context manager for handling optimization lifecycle.
    Automatically updates optimization status to "completed" or "cancelled" based on context exit.
    """

    def __init__(
        self,
        client: Opik,
        dataset_name: str,
        objective_name: str,
        name: str | None = None,
        metadata: dict[str, Any] | None = None,
        optimization_id: str | None = None,
    ):
        """
        Initialize the optimization context.

        Args:
            client: The Opik client instance
            dataset_name: Name of the dataset for optimization
            objective_name: Name of the optimization objective
            name: Optional name for the optimization
            metadata: Optional metadata for the optimization
        """
        self.client = client
        self.dataset_name = dataset_name
        self.objective_name = objective_name
        self.name = name
        self.metadata = metadata
        self.optimization_id = optimization_id
        self.optimization: Optimization | None = None

    def __enter__(self) -> Optimization | None:
        """Create and return the optimization."""
        try:
            self.optimization = self.client.create_optimization(
                dataset_name=self.dataset_name,
                objective_name=self.objective_name,
                name=self.name,
                metadata=self.metadata,
                optimization_id=self.optimization_id,
            )

            if self.optimization:
                return self.optimization
            else:
                return None
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            logger.warning("Continuing without Opik optimization tracking.")
            return None

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_val: BaseException | None,
        exc_tb: TracebackType | None,
    ) -> Literal[False]:
        """Update optimization status based on context exit."""
        if self.optimization is None:
            return False

        try:
            if exc_type is None:
                self.optimization.update(status="completed")
            else:
                self.optimization.update(status="cancelled")
        except Exception as e:
            logger.error(f"Failed to update optimization status: {e}")

        return False


def format_prompt(prompt: str, **kwargs: Any) -> str:
    """
    Format a prompt string with the given keyword arguments.

    Args:
        prompt: The prompt string to format
        **kwargs: Keyword arguments to format into the prompt

    Returns:
        str: The formatted prompt string

    Raises:
        ValueError: If any required keys are missing from kwargs
    """
    try:
        return prompt.format(**kwargs)
    except KeyError as e:
        raise ValueError(f"Missing required key in prompt: {e}")


def validate_prompt(prompt: str) -> bool:
    """
    Validate a prompt string.

    Args:
        prompt: The prompt string to validate

    Returns:
        bool: True if the prompt is valid, False otherwise
    """
    if not prompt or not prompt.strip():
        return False
    return True


def setup_logging(log_level: str = "INFO") -> None:
    """
    Setup logging configuration.

    Args:
        log_level: The log level to use (default: INFO)
    """
    valid_levels = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
    if log_level not in valid_levels:
        raise ValueError(f"Invalid log level. Must be one of {valid_levels}")

    numeric_level = getattr(logging, log_level.upper())
    logging.basicConfig(level=numeric_level)


def get_random_seed() -> int:
    """
    Get a random seed for reproducibility.

    Returns:
        int: A random seed
    """
    import random

    return random.randint(0, 2**32 - 1)


def random_chars(n: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


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


def optimization_context(
    client: Opik,
    dataset_name: str,
    objective_name: str,
    name: str | None = None,
    metadata: dict[str, Any] | None = None,
    optimization_id: str | None = None,
) -> OptimizationContextManager:
    """
    Create a context manager for handling optimization lifecycle.
    Automatically updates optimization status to "completed" or "cancelled" based on context exit.

    Args:
        client: The Opik client instance
        dataset_name: Name of the dataset for optimization
        objective_name: Name of the optimization objective
        name: Optional name for the optimization
        metadata: Optional metadata for the optimization

    Returns:
        OptimizationContextManager: A context manager that handles optimization lifecycle
    """
    return OptimizationContextManager(
        client=client,
        dataset_name=dataset_name,
        objective_name=objective_name,
        name=name,
        metadata=metadata,
        optimization_id=optimization_id,
    )


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


def get_trial_compare_url(
    *, dataset_id: str | None, optimization_id: str | None, trial_ids: list[str]
) -> str:
    if dataset_id is None or optimization_id is None:
        raise ValueError("dataset_id and optimization_id are required")
    if not trial_ids:
        raise ValueError("trial_ids must be a non-empty list")

    opik_config = opik.config.get_from_user_inputs()
    url_override = opik_config.url_override
    base = ensure_ending_slash(url_override)

    trials_query = urllib.parse.quote(json.dumps(trial_ids))
    compare_path = (
        f"optimizations/{optimization_id}/{dataset_id}/compare?trials={trials_query}"
    )
    return urllib.parse.urljoin(base, compare_path)


def function_to_tool_definition(
    func: Callable, description: str | None = None
) -> dict[str, Any]:
    sig = inspect.signature(func)
    doc = description or func.__doc__ or ""

    properties: dict[str, dict[str, str]] = {}
    required: list[str] = []

    for name, param in sig.parameters.items():
        param_type = (
            param.annotation if param.annotation != inspect.Parameter.empty else str
        )
        json_type = python_type_to_json_type(param_type)
        properties[name] = {"type": json_type, "description": f"{name} parameter"}
        if param.default == inspect.Parameter.empty:
            required.append(name)

    return {
        "type": "function",
        "function": {
            "name": getattr(func, "__name__", "<unknown>"),
            "description": doc.strip(),
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required,
            },
        },
    }


def python_type_to_json_type(python_type: type) -> str:
    # Basic type mapping
    if python_type in [str]:
        return "string"
    elif python_type in [int]:
        return "integer"
    elif python_type in [float]:
        return "number"
    elif python_type in [bool]:
        return "boolean"
    elif python_type in [dict]:
        return "object"
    elif python_type in [list, list]:
        return "array"
    else:
        return "string"  # default fallback


# =============================================================================
# Deprecation: Wikipedia functions moved to tools/wikipedia.py
# =============================================================================


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
