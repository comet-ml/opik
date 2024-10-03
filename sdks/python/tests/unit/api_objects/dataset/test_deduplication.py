import unittest
from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset


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

    # Verify the content of the inserted item
    inserted_item = inserted_items[0]
    assert inserted_item.input == item_dict["input"], "Input mismatch"
    assert (
        inserted_item.expected_output == item_dict["expected_output"]
    ), "Expected output mismatch"
    assert inserted_item.metadata == item_dict["metadata"], "Metadata mismatch"


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

    # Verify the content of the inserted items
    assert inserted_items[0].input == item_dict1["input"], "First item input mismatch"
    assert (
        inserted_items[0].expected_output == item_dict1["expected_output"]
    ), "First item expected output mismatch"
    assert (
        inserted_items[0].metadata == item_dict1["metadata"]
    ), "First item metadata mismatch"

    assert inserted_items[1].input == item_dict2["input"], "Second item input mismatch"
    assert (
        inserted_items[1].expected_output == item_dict2["expected_output"]
    ), "Second item expected output mismatch"
    assert (
        inserted_items[1].metadata == item_dict2["metadata"]
    ), "Second item metadata mismatch"


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

    # Verify the content of the inserted items
    assert inserted_items[0].input == item_dict1["input"], "First item input mismatch"
    assert (
        inserted_items[0].expected_output == item_dict1["expected_output"]
    ), "First item expected output mismatch"
    assert (
        inserted_items[0].metadata == item_dict1["metadata"]
    ), "First item metadata mismatch"

    assert inserted_items[1].input == item_dict2["input"], "Second item input mismatch"
    assert (
        inserted_items[1].expected_output == item_dict2["expected_output"]
    ), "Second item expected output mismatch"
    assert (
        inserted_items[1].metadata == item_dict2["metadata"]
    ), "Second item metadata mismatch"


if __name__ == "__main__":
    unittest.main()
