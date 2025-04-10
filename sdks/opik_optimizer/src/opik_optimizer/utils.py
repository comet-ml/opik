"""Utility functions and constants for the optimizer package."""

import opik
from opik.api_objects.opik_client import Opik
from opik.api_objects.dataset.dataset_item import DatasetItem
from typing import List, Dict, Any, Optional

# Test dataset name for optimizer examples
TEST_DATASET_NAME = "tiny-test-optimizer"

# Default model for optimizers
DEFAULT_MODEL = "o3-mini"


def get_or_create_dataset(
    dataset_name: str,
    description: str,
    data: Optional[List[Dict[str, Any]]] = None,
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

        if data:
            # Convert data to DatasetItem objects
            dataset_items = [
                DatasetItem(
                    text=item["text"],
                    label=item["label"],
                    metadata=item.get("metadata", {}),
                )
                for item in data
            ]

            # Insert the data
            dataset.__internal_api__insert_items_as_dataclasses__(dataset_items)

            # Verify data was added
            if not dataset.get_items():
                raise Exception("Failed to add data to dataset")

    return dataset
