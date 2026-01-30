from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset


def test_insert_deduplication__two_dicts_passed_with_the_same_content__only_one_is_inserted():
    mock_rest_client = Mock()

    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    item_dict = {
        "input": {"key": "value", "key2": "value2"},
        "expected_output": {"key": "value", "key2": "value2"},
        "metadata": {"key": "value", "key2": "value2"},
    }

    # Insert the identical items
    dataset.insert([item_dict, item_dict])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    assert len(inserted_items) == 1, "Only one item should be inserted"


def test_insert_deduplication__two_dicts_passed_with_the_different_content__both_are_inserted():
    mock_rest_client = Mock()

    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

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

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    assert len(inserted_items) == 2, "Two items should be inserted"


def test_insert_deduplication__three_dicts_passed__one_unique__two_duplicates__two_different_items_are_inserted():
    mock_rest_client = Mock()

    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

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

    # Insert 3 items: one unique and two duplicates
    dataset.insert([item_dict1, item_dict2, item_dict1])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_rest_items = call_args[1]["items"]

    assert len(inserted_rest_items) == 2, "Two items should be inserted"


def test_update__happyflow():
    mock_rest_client = Mock()

    dataset = Dataset("test_dataset", "Test description", mock_rest_client)

    initial_item = {
        "input": {"key": "initial_value"},
        "expected_output": {"key": "initial_output"},
        "metadata": {"key": "initial_metadata"},
    }

    dataset.insert([initial_item])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called once for insertion"
    )

    insert_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    inserted_items = insert_call_args[1]["items"]

    assert len(inserted_items) == 1, "One item should be inserted"

    # Create an updated version of the item
    updated_item = {
        "id": inserted_items[0].id,
        "input": {"key": "updated_value"},
        "expected_output": {"key": "updated_output"},
        "metadata": {"key": "updated_metadata"},
    }

    # Update the item
    dataset.update([updated_item])

    # Check that create_or_update_dataset_items was called twice in total (once for insertion, once for update)
    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2, (
        "create_or_update_dataset_items should be called twice in total"
    )

    # Get the arguments passed to create_or_update_dataset_items for update
    update_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    updated_rest_items = update_call_args[1]["items"]

    # Check that one item was updated
    assert len(updated_rest_items) == 1, "One item should be updated"

    # Verify the content of the updated item
    assert updated_rest_items[0].data["input"] == {"key": "updated_value"}, (
        "Input should be updated"
    )
    assert updated_rest_items[0].data["expected_output"] == {"key": "updated_output"}, (
        "Expected output should be updated"
    )
    assert updated_rest_items[0].data["metadata"] == {"key": "updated_metadata"}, (
        "Metadata should be updated"
    )
