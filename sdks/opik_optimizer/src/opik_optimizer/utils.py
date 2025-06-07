"""Utility functions and constants for the optimizer package."""

from typing import List, Dict, Any, Optional, Callable, TYPE_CHECKING, Final

import opik
from opik.api_objects.opik_client import Opik

import logging
import random
import string
import base64
import urllib.parse
from rich import console


# Type hint for OptimizationResult without circular import
if TYPE_CHECKING:
    from .optimization_result import OptimizationResult

logger = logging.getLogger(__name__)
ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="


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


def ensure_ending_slash(url: str) -> str:
    return url.rstrip("/") + "/"


def get_optimization_run_url_by_id(
    dataset_id: str, optimization_id: str, url_override: str
) -> str:
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    run_path = urllib.parse.quote(
        f"v1/session/redirect/optimizations/?optimization_id={optimization_id}&dataset_id={dataset_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ensure_ending_slash(url_override), run_path)


def display_optimization_run_link(
    optimization_id: str, dataset_id: str, url_override: str
) -> None:
    console_container = console.Console()

    optimization_url = get_optimization_run_url_by_id(
        optimization_id=optimization_id,
        dataset_id=dataset_id,
        url_override=url_override,
    )
    console_container.print(
        f"View the optimization run [link={optimization_url}]in your Opik dashboard[/link]."
    )
