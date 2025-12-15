"""
Demo datasets for Optimization Studio templates.

These datasets are created for all new users to demonstrate
Optimization Studio capabilities.
"""

import json
from pathlib import Path

_DATASETS_DIR = Path(__file__).parent


def load_dataset(name: str) -> dict:
    """Load a demo dataset from JSON file."""
    file_path = _DATASETS_DIR / f"{name}.json"
    with open(file_path, "r") as f:
        return json.load(f)


def get_all_optimization_studio_datasets() -> list[dict]:
    """Load all Optimization Studio template datasets."""
    dataset_files = [
        "chatbot_training",
        "text_to_sql",
        "structured_output",
    ]
    return [load_dataset(name) for name in dataset_files]

