import pytest
import os
from opik import Opik
from playwright.sync_api import Page
from page_objects.DatasetsPage import DatasetsPage
from page_objects.DatasetItemsPage import DatasetItemsPage
from datasets_utils import TEST_ITEMS
import json


@pytest.fixture
def insert_dataset_items_sdk(client: Opik, create_delete_dataset_sdk):
    dataset = client.get_dataset(create_delete_dataset_sdk)
    dataset.insert(TEST_ITEMS)
