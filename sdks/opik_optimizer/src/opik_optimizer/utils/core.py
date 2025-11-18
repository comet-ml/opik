"""Utility functions and constants for the optimizer package."""

from typing import (
    Any,
    Final,
    Literal,
    TYPE_CHECKING,
)
from collections.abc import Callable

import ast
import inspect
import base64
import json
import logging
import random
import string
import urllib.parse
from types import TracebackType

import requests

import opik
from opik.api_objects.opik_client import Opik
from opik.api_objects.optimization import Optimization

from .colbert import ColBERTv2

if TYPE_CHECKING:
    from ..optimizable_agent import OptimizableAgent
    from ..api_objects import chat_prompt

ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="
logger = logging.getLogger(__name__)


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


def disable_experiment_reporting() -> None:
    import opik.evaluation.report

    opik.evaluation.report._patch_display_experiment_results = (
        opik.evaluation.report.display_experiment_results
    )
    opik.evaluation.report._patch_display_experiment_link = (
        opik.evaluation.report.display_experiment_link
    )
    opik.evaluation.report.display_experiment_results = lambda *args, **kwargs: None
    opik.evaluation.report.display_experiment_link = lambda *args, **kwargs: None


def enable_experiment_reporting() -> None:
    import opik.evaluation.report

    try:
        opik.evaluation.report.display_experiment_results = (
            opik.evaluation.report._patch_display_experiment_results
        )
        opik.evaluation.report.display_experiment_link = (
            opik.evaluation.report._patch_display_experiment_link
        )
    except AttributeError:
        pass


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


def create_litellm_agent_class(
    prompt: "chat_prompt.ChatPrompt", optimizer_ref: Any = None
) -> type["OptimizableAgent"]:
    """
    Create a LiteLLMAgent from a chat prompt.

    Args:
        prompt: The chat prompt to use
        optimizer_ref: Optional optimizer instance to attach to the agent
    """
    from opik_optimizer.optimizable_agent import OptimizableAgent

    if prompt.invoke is not None:

        class LiteLLMAgent(OptimizableAgent):
            model = prompt.model
            model_kwargs = prompt.model_kwargs
            optimizer = optimizer_ref

            def __init__(
                self, prompt: "chat_prompt.ChatPrompt", project_name: str | None = None
            ) -> None:
                # Get project_name from optimizer if available
                if project_name is None and hasattr(self.optimizer, "project_name"):
                    project_name = self.optimizer.project_name
                super().__init__(prompt, project_name=project_name)

            def invoke(
                self, messages: list[dict[str, str]], seed: int | None = None
            ) -> str:
                return prompt.invoke(
                    self.model, messages, prompt.tools, **self.model_kwargs
                )  # type: ignore[misc]

    else:

        class LiteLLMAgent(OptimizableAgent):  # type: ignore[no-redef]
            model = prompt.model
            model_kwargs = prompt.model_kwargs
            optimizer = optimizer_ref

            def __init__(
                self, prompt: "chat_prompt.ChatPrompt", project_name: str | None = None
            ) -> None:
                # Get project_name from optimizer if available
                if project_name is None and hasattr(self.optimizer, "project_name"):
                    project_name = self.optimizer.project_name
                super().__init__(prompt, project_name=project_name)

    return LiteLLMAgent


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
            "name": func.__name__,
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


def search_wikipedia(query: str, use_api: bool | None = False) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.

    Args:
        query: The search query string
        use_api: (Optional) If True, directly use Wikipedia API instead of ColBERTv2.
                If False (default), try ColBERTv2 first with API fallback.
    """
    if use_api:
        # Directly use Wikipedia API when requested
        try:
            return _search_wikipedia_api(query)
        except Exception as api_error:
            print(f"Wikipedia API failed: {api_error}")
            return [f"Wikipedia search unavailable. Query was: {query}"]

    # Default behavior: Try ColBERTv2 first with API fallback
    # Try ColBERTv2 first with a short timeout
    try:
        colbert = ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")
        # Use a shorter timeout by modifying the max_retries parameter
        results = colbert(query, k=3, max_retries=1)
        return [str(item.text) for item in results if hasattr(item, "text")]
    except Exception:
        # Fallback to Wikipedia API
        try:
            return _search_wikipedia_api(query)
        except Exception as api_error:
            print(f"Wikipedia API fallback also failed: {api_error}")
            return [f"Wikipedia search unavailable. Query was: {query}"]


def _search_wikipedia_api(query: str, max_results: int = 3) -> list[str]:
    """
    Fallback Wikipedia search using the Wikipedia API.
    """
    try:
        # First, search for pages using the search API
        search_params: dict[str, str | int] = {
            "action": "query",
            "format": "json",
            "list": "search",
            "srsearch": query,
            "srlimit": max_results,
            "srprop": "snippet",
        }

        headers = {
            "User-Agent": "OpikOptimizer/1.0 (https://github.com/opik-ai/opik-optimizer)"
        }
        search_response = requests.get(
            "https://en.wikipedia.org/w/api.php",
            params=search_params,
            headers=headers,
            timeout=5,
        )

        if search_response.status_code != 200:
            raise Exception(f"Search API returned status {search_response.status_code}")

        search_data = search_response.json()

        results = []
        if "query" in search_data and "search" in search_data["query"]:
            for item in search_data["query"]["search"][:max_results]:
                page_title = item["title"]
                snippet = item.get("snippet", "")

                # Clean up the snippet (remove HTML tags)
                import re

                clean_snippet = re.sub(r"<[^>]+>", "", snippet)
                clean_snippet = re.sub(r"&[^;]+;", " ", clean_snippet)

                if clean_snippet.strip():
                    results.append(f"{page_title}: {clean_snippet.strip()}")

        return results if results else [f"No Wikipedia results found for: {query}"]

    except Exception as e:
        raise Exception(f"Wikipedia API request failed: {e}") from e
