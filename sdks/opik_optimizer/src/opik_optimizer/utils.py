"""Utility functions and constants for the optimizer package."""

import opik
import logging
import random
import string
from opik.api_objects.opik_client import Opik

from typing import List, Dict, Any, Optional, Callable, TYPE_CHECKING

# Test dataset name for optimizer examples
TEST_DATASET_NAME = "tiny-test-optimizer"

# Type hint for OptimizationResult without circular import
if TYPE_CHECKING:
    from .optimization_result import OptimizationResult

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


def get_or_create_dataset(
    dataset_name: str,
    description: str,
    data_loader: Callable[[], List[Dict[str, Any]]],
    project_name: Optional[str] = None,
) -> opik.Dataset:
    """
    Get an existing dataset or create a new one if it doesn't exist.

    Args:
        dataset_name: Name of the dataset
        description: Description of the dataset
        data: Optional data to insert into the dataset
        project_name: Optional project name

    Returns:
        opik.Dataset: The dataset object
    """
    client = Opik(project_name=project_name)

    try:
        # Try to get existing dataset
        dataset = client.get_dataset(dataset_name)
        # If dataset exists but has no data, delete it
        if not dataset.get_items():
            print("Dataset exists but is empty - deleting it...")
            # Delete all items in the dataset
            items = dataset.get_items()
            if items:
                dataset.delete(items_ids=[item.id for item in items])
            # Delete the dataset itself
            client.delete_dataset(dataset_name)
            raise Exception("Dataset deleted, will create new one")
    except Exception:
        # Create new dataset
        print("Creating new dataset...")
        dataset = client.create_dataset(name=dataset_name, description=description)

        dataset_items = data_loader()
        dataset.insert(dataset_items)

        # Verify data was added
        if not dataset.get_items():
            raise Exception("Failed to add data to dataset")

    return dataset


def random_chars(n: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))
