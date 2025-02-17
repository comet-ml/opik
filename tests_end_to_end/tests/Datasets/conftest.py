import pytest
from opik import Opik
from Datasets.datasets_utils import TEST_ITEMS


@pytest.fixture
def insert_dataset_items_sdk(client: Opik, create_dataset_sdk):
    dataset = client.get_dataset(create_dataset_sdk)
    dataset.insert(TEST_ITEMS)
