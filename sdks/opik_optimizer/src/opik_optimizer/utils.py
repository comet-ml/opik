"""Utility functions and constants for the optimizer package."""

import json
import logging
import random
import string
from typing import Any

logger = logging.getLogger(__name__)


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


def disable_experiment_reporting():
    import opik.evaluation.report
    
    opik.evaluation.report._patch_display_experiment_results = opik.evaluation.report.display_experiment_results
    opik.evaluation.report._patch_display_experiment_link = opik.evaluation.report.display_experiment_link
    opik.evaluation.report.display_experiment_results = lambda *args, **kwargs: None
    opik.evaluation.report.display_experiment_link = lambda *args, **kwargs: None

def enable_experiment_reporting():
    import opik.evaluation.report

    try:
        opik.evaluation.report.display_experiment_results = opik.evaluation.report._patch_display_experiment_results
        opik.evaluation.report.display_experiment_link = opik.evaluation.report._patch_display_experiment_link
    except AttributeError:
        pass
    
def json_to_dict(json_str: str) -> Any:
    cleaned_json_string = json_str.strip()

    try:
        return json.loads(cleaned_json_string)
    except json.JSONDecodeError:
        if cleaned_json_string.startswith("```json"):
            cleaned_json_string = cleaned_json_string[7:] 
            if cleaned_json_string.endswith("```"):
                cleaned_json_string = cleaned_json_string[:-3]
        elif cleaned_json_string.startswith("```"):
            cleaned_json_string = cleaned_json_string[3:]
            if cleaned_json_string.endswith("```"):
                cleaned_json_string = cleaned_json_string[:-3]
        
        return json.loads(cleaned_json_string)
