"""Utility functions and constants for the optimizer package."""

import opik
from opik.api_objects.opik_client import Opik
from opik.api_objects.dataset.dataset_item import DatasetItem
from typing import List, Dict, Any, Optional, Callable

# Test dataset name for optimizer examples
TEST_DATASET_NAME = "tiny-test-optimizer"

# Default model for optimizers
DEFAULT_MODEL = "o3-mini"

def get_or_create_dataset(
    dataset_name: str,
    description: str,
    data_loader: Callable[[], List[Dict[str, Any]]],
    project_name: Optional[str] = None
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
        dataset = client.create_dataset(
            name=dataset_name,
            description=description
        )
        
        dataset_items = data_loader()
        dataset.insert(dataset_items)

        # Verify data was added
        if not dataset.get_items():
            raise Exception("Failed to add data to dataset")

    return dataset
