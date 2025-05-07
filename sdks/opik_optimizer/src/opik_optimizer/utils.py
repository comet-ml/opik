"""Utility functions and constants for the optimizer package."""

import opik
import logging
import tqdm
import random
import string
from opik.api_objects.opik_client import Opik
import sys
import os

from typing import List, Dict, Any, Optional, Callable, TYPE_CHECKING

# Test dataset name for optimizer examples
TEST_DATASET_NAME = "tiny-test-optimizer"

try:
    from rich import print as rprint
    from rich.panel import Panel
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False
    # Define a dummy rprint if rich is not installed
    def rprint(*args, **kwargs):
        print(*args, **kwargs)
    Panel = None # Define Panel as None if rich is not available

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


def _in_colab_environment() -> bool:
    """Check if running in Google Colab."""
    return "COLAB_GPU" in os.environ

def _in_jupyter_notebook_environment() -> bool:
    """Check specifically for Jupyter Notebook/Lab, not just IPython terminal."""
    try:
        # Check for __IPYTHON__ attribute added by IPython
        from IPython import get_ipython
        ipython = get_ipython()
        if ipython is None:
            return False
    except ImportError:
        return False

    # Check if the kernel indicates a notebook-like environment
    shell = ipython.__class__.__name__
    if shell == 'ZMQInteractiveShell': # Jupyter notebook or qtconsole
        return True
    # Handle potential 'google.colab._shell' or similar specific shells if needed
    elif 'colab' in str(ipython.__class__).lower(): # Check for Colab specific shell
        return True
    # elif shell == 'TerminalInteractiveShell': # Terminal running IPython
    #     return False # Explicitly false for terminal IPython
    else: # Other non-notebook environments (like Spyder, terminal Python)
        return False

_tqdm = None
_tqdm_cls = None # Store the determined class

def get_tqdm():
    """
    Gets the appropriate tqdm class for the environment, handling import errors.
    Prefers notebook version in Jupyter/Colab.
    """
    global _tqdm, _tqdm_cls
    if _tqdm_cls is None: # Determine class only once
        try:
            from tqdm import tqdm as tqdm_base
            from tqdm.notebook import tqdm as tqdm_notebook

            if _in_jupyter_notebook_environment() or _in_colab_environment():
                logger.debug("Using tqdm.notebook.tqdm")
                _tqdm_cls = tqdm_notebook
            else:
                logger.debug("Using tqdm.tqdm")
                _tqdm_cls = tqdm_base
            _tqdm = _tqdm_cls

        except ImportError:
            logger.warning("tqdm is not installed. Progress bars will be disabled.")
            def dummy_tqdm(*args, **kwargs):
                if args:
                    return args[0]
                return None
            _tqdm = dummy_tqdm
            _tqdm_cls = dummy_tqdm

    return _tqdm_cls


def random_chars(n: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


def display_optimization_result(result: 'OptimizationResult'):
    """
    Displays the OptimizationResult using rich formatting if available,
    otherwise falls back to standard print.

    Args:
        result: The OptimizationResult object to display.
    """
    if not hasattr(result, '__rich__') and not hasattr(result, '__str__'):
        logger.warning(f"Cannot display result of type {type(result)}. Expecting OptimizationResult.")
        print(result) # Default print as fallback
        return

    if RICH_AVAILABLE:
        # rich.print will automatically use __rich__ if available, else __str__
        rprint(result)
    else:
        # Fallback to standard print using the object's __str__ method
        print(result)