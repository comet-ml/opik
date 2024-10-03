import unittest
from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset
from opik.api_objects.dataset.dataset_item import DatasetItem


def test_insert_deduplication():
    # Create a mock REST client
    mock_rest_client = Mock()

    # Create a Dataset instance
    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    # Create two identical dictionaries
    item_dict = {
        "input": {"key": "value", "key2": "value2"},
        "expected_output": {"key": "value", "key2": "value2"},
        "metadata": {"key": "value", "key2": "value2"},
    }

    # Insert the identical items
    dataset.insert([item_dict, item_dict])

    # Check that create_or_update_dataset_items was called only once
    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1
    ), "create_or_update_dataset_items should be called only once"

    # Get the arguments passed to create_or_update_dataset_items
    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    # Check that only one item was inserted
    assert len(inserted_items) == 1, "Only one item should be inserted"


def test_insert_deduplication_with_different_items():
    # Create a mock REST client
    mock_rest_client = Mock()

    # Create a Dataset instance
    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    # Create two different dictionaries
    item_dict1 = {
        "input": {"key": "value1"},
        "expected_output": {"key": "output1"},
        "metadata": {"key": "meta1"},
    }
    item_dict2 = {
        "input": {"key": "value2"},
        "expected_output": {"key": "output2"},
        "metadata": {"key": "meta2"},
    }

    # Insert the different items
    dataset.insert([item_dict1, item_dict2])

    # Check that create_or_update_dataset_items was called only once
    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1
    ), "create_or_update_dataset_items should be called only once"

    # Get the arguments passed to create_or_update_dataset_items
    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    # Check that two items were inserted
    assert len(inserted_items) == 2, "Two items should be inserted"


def test_insert_deduplication_with_partial_overlap():
    # Create a mock REST client
    mock_rest_client = Mock()

    # Create a Dataset instance
    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    # Create three dictionaries, two of which are identical
    item_dict1 = {
        "input": {"key": "value1"},
        "expected_output": {"key": "output1"},
        "metadata": {"key": "meta1"},
    }
    item_dict2 = {
        "input": {"key": "value2"},
        "expected_output": {"key": "output2"},
        "metadata": {"key": "meta2"},
    }

    # Insert the items
    dataset.insert([item_dict1, item_dict2, item_dict1])

    # Check that create_or_update_dataset_items was called only once
    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1
    ), "create_or_update_dataset_items should be called only once"

    # Get the arguments passed to create_or_update_dataset_items
    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    # Check that two items were inserted
    assert len(inserted_items) == 2, "Two items should be inserted"


def test_update_flow():
    # Create a mock REST client
    mock_rest_client = Mock()

    # Create a Dataset instance
    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    # Create an initial item
    initial_item = {
        "input": {"key": "initial_value"},
        "expected_output": {"key": "initial_output"},
        "metadata": {"key": "initial_metadata"},
    }

    # Insert the initial item
    dataset.insert([initial_item])

    # Check that create_or_update_dataset_items was called once for insertion
    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1
    ), "create_or_update_dataset_items should be called once for insertion"

    # Get the arguments passed to create_or_update_dataset_items for insertion
    insert_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    inserted_items = insert_call_args[1]["items"]

    # Check that one item was inserted
    assert len(inserted_items) == 1, "One item should be inserted"

    # Create an updated version of the item
    updated_item = DatasetItem(
        id=inserted_items[0].id,
        input={"key": "updated_value"},
        expected_output={"key": "updated_output"},
        metadata={"key": "updated_metadata"},
    )

    # Update the item
    dataset.update([updated_item])

    # Check that create_or_update_dataset_items was called twice in total (once for insertion, once for update)
    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2
    ), "create_or_update_dataset_items should be called twice in total"

    # Get the arguments passed to create_or_update_dataset_items for update
    update_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    updated_items = update_call_args[1]["items"]

    # Check that one item was updated
    assert len(updated_items) == 1, "One item should be updated"

    # Verify the content of the updated item
    assert updated_items[0].input == {"key": "updated_value"}, "Input should be updated"
    assert updated_items[0].expected_output == {
        "key": "updated_output"
    }, "Expected output should be updated"
    assert updated_items[0].metadata == {
        "key": "updated_metadata"
    }, "Metadata should be updated"


if __name__ == "__main__":
    unittest.main()
