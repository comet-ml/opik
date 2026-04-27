import opik
import opik.exceptions
from opik import synchronization

from opik.api_objects.dataset import dataset_item
from opik.api_objects import helpers
from . import verifiers
from ..testlib import generate_project_name
import pytest

PROJECT_NAME = generate_project_name("e2e", __name__)


def test_create_and_populate_dataset__happyflow(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
            },
        ]
    )

    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of France?"},
            expected_output={"output": "Paris"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Germany?"},
            expected_output={"output": "Berlin"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Poland?"},
            expected_output={"output": "Warsaw"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
        project_name=PROJECT_NAME,
    )


def test_insert_and_update_item__dataset_size_should_be_the_same__an_item_with_the_same_id_should_have_new_content(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    ITEM_ID = helpers.generate_id()
    dataset.insert(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of France?"},
            },
        ]
    )
    dataset.update(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of Belarus?"},
            },
        ]
    )
    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Belarus?"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
        project_name=PROJECT_NAME,
    )


def test_deduplication(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    item = {
        "user_input": {"question": "What is the of capital of France?"},
        "expected_model_output": {"output": "Paris"},
    }

    # Write the dataset
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert([item])

    # Read the dataset and insert the same item
    new_dataset = opik_client.get_dataset(dataset_name, project_name=PROJECT_NAME)
    new_dataset.insert([item])

    # Verify the dataset
    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[
            dataset_item.DatasetItem(**item),
        ],
        project_name=PROJECT_NAME,
    )


def test_dataset_clearing(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
        ]
    )
    dataset.clear()

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[],
        project_name=PROJECT_NAME,
    )


def test_get_items_with_filter__returns_filtered_items(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_items with filter_string returns correct filtered items."""
    DESCRIPTION = "E2E test dataset for filtering"

    # Create dataset with items that have different data.category values
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
                "category": "geography",
            },
            {
                "input": {"question": "What is 2 + 2?"},
                "expected_output": {"output": "4"},
                "category": "math",
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
                "category": "geography",
            },
        ]
    )

    verifiers.verify_dataset_filtered_items(
        opik_client=opik_client,
        dataset_name=dataset_name,
        filter_string='data.category = "geography"',
        expected_count=2,
        expected_inputs={
            "What is the capital of France?",
            "What is the capital of Poland?",
        },
        project_name=PROJECT_NAME,
    )


def test_get_items_with_filter__filter_excludes_all_items__returns_empty_list(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_items with filter that matches no items returns empty list."""
    DESCRIPTION = "E2E test dataset for empty filter"

    # Create dataset with items
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is 2 + 2?"},
                "expected_output": {"output": "4"},
            },
        ]
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "category": "geography",
            },
            {
                "input": {"question": "What is the capital of Germany?"},
                "category": "geography",
            },
        ]
    )

    verifiers.verify_dataset_filtered_items(
        opik_client=opik_client,
        dataset_name=dataset_name,
        filter_string='data.category = "nonexistent"',
        expected_count=0,
        expected_inputs=set(),
        project_name=PROJECT_NAME,
    )


def _wait_for_version(dataset, expected_version: str, timeout: float = 10) -> None:
    """Wait for dataset to have the expected version, fail if not reached."""
    success = synchronization.until(
        lambda: dataset.get_current_version_name() == expected_version,
        max_try_seconds=timeout,
    )
    assert success, f"Expected version '{expected_version}' was not created in time"


def test_get_version_view__returns_items_from_specific_version(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_version_view returns items from a specific dataset version.

    Also tests that get_current_version_name returns correct version after mutations.
    """
    DESCRIPTION = "E2E test dataset for version view"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    # Version should be None before any items are inserted
    assert dataset.get_current_version_name() is None

    # Insert first batch of items - creates v1
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
            },
        ]
    )
    _wait_for_version(dataset, "v1")

    # Insert second batch of items - creates v2
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
        ]
    )
    _wait_for_version(dataset, "v2")

    # Get version view for v1 - should only have 1 item
    v1_view = dataset.get_version_view("v1")
    v1_items = v1_view.get_items()
    assert len(v1_items) == 1
    assert v1_items[0]["input"] == {"question": "What is the capital of France?"}
    assert v1_view.version_name == "v1"
    assert v1_view.items_total == 1
    assert v1_view.project_name == PROJECT_NAME

    # Get version view for v2 - should have 2 items
    v2_view = dataset.get_version_view("v2")
    v2_items = v2_view.get_items()
    assert len(v2_items) == 2
    assert v2_view.version_name == "v2"
    assert v2_view.items_total == 2
    assert v2_view.project_name == PROJECT_NAME

    # Current dataset should also have 2 items
    current_items = dataset.get_items()
    assert len(current_items) == 2

    # Delete an item - should create v3
    dataset.delete([current_items[0]["id"]])
    _wait_for_version(dataset, "v3")

    # Get version view for v3 - should have 1 item
    v3_view = dataset.get_version_view("v3")
    v3_items = v3_view.get_items()
    assert len(v3_items) == 1
    assert v3_view.version_name == "v3"
    assert v3_view.items_total == 1
    assert v3_view.project_name == PROJECT_NAME


def test_get_version_view__version_not_found__raises_exception(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_version_view raises DatasetVersionNotFound for non-existent version."""
    DESCRIPTION = "E2E test dataset for version not found"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    # Insert items to create v1
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
            },
        ]
    )
    _wait_for_version(dataset, "v1")

    # Try to get a non-existent version
    with pytest.raises(opik.exceptions.DatasetVersionNotFound):
        dataset.get_version_view("v999")


def test_dataset_items_count__returns_correct_count_after_insert(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that dataset_items_count returns the correct count after insert."""
    dataset = opik_client.create_dataset(dataset_name, description="items_count test")

    dataset.insert(
        [
            {"input": {"question": "What is 2+2?"}},
            {"input": {"question": "What is 3+3?"}},
            {"input": {"question": "What is 4+4?"}},
        ]
    )

    success = synchronization.until(
        lambda: dataset.dataset_items_count == 3,
        max_try_seconds=30,
    )
    assert success, f"Expected dataset_items_count=3, got {dataset.dataset_items_count}"
