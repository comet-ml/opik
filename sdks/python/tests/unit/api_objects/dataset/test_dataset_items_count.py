from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset
from opik.rest_api.types.dataset_public import DatasetPublic


def test_dataset_items_count__cached_value__returns_cached_count():
    """Test that dataset_items_count returns cached value when available."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=5
    )

    # Access the count
    count = dataset.dataset_items_count

    # Should return cached value without calling backend
    assert count == 5
    mock_rest_client.datasets.get_dataset_by_identifier.assert_not_called()


def test_dataset_items_count__no_cached_value__fetches_from_backend():
    """Test that dataset_items_count fetches from backend when cache is None."""
    mock_rest_client = Mock()

    # Mock the backend response
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=10)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Create dataset without initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=None
    )

    # Access the count
    count = dataset.dataset_items_count

    # Should fetch from backend
    assert count == 10
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )


def test_dataset_items_count__fetched_once__cached_for_subsequent_calls():
    """Test that dataset_items_count is fetched once and then cached."""
    mock_rest_client = Mock()

    # Mock the backend response
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=10)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Create dataset without initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=None
    )

    # Access the count multiple times
    count1 = dataset.dataset_items_count
    count2 = dataset.dataset_items_count
    count3 = dataset.dataset_items_count

    # Should fetch from backend only once
    assert count1 == 10
    assert count2 == 10
    assert count3 == 10
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )


def test_delete__invalidates_cached_count():
    """Test that delete() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=5
    )

    # Verify initial count
    assert dataset.dataset_items_count == 5

    # Delete some items
    dataset.delete(["item1", "item2"])

    # Mock the backend response for the next count fetch
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=3)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Access count again - should fetch from backend
    count = dataset.dataset_items_count

    assert count == 3
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )


def test_update__invalidates_cached_count():
    """Test that update() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=5
    )

    # Verify initial count
    assert dataset.dataset_items_count == 5

    # Update an item
    updated_item = {
        "id": "item1",
        "input": {"key": "updated_value"},
        "expected_output": {"key": "updated_output"},
    }
    dataset.update([updated_item])

    # Mock the backend response for the next count fetch
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=5)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Access count again - should fetch from backend
    count = dataset.dataset_items_count

    assert count == 5
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )


def test_insert__invalidates_cached_count():
    """Test that insert() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=5
    )

    # Verify initial count
    assert dataset.dataset_items_count == 5

    # Insert new items
    new_items = [
        {
            "input": {"key": "value1"},
            "expected_output": {"key": "output1"},
        },
        {
            "input": {"key": "value2"},
            "expected_output": {"key": "output2"},
        },
    ]
    dataset.insert(new_items)

    # Mock the backend response for the next count fetch
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=7)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Access count again - should fetch from backend
    count = dataset.dataset_items_count

    assert count == 7
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )


def test_backend_returns_none_count__property_returns_none():
    """Test that if backend returns None for count, property returns None."""
    mock_rest_client = Mock()

    # Mock the backend response with None count
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=None)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Create dataset without initial count
    dataset = Dataset(
        "test_dataset", "Test description", mock_rest_client, dataset_items_count=None
    )

    # Access the count
    count = dataset.dataset_items_count

    # Should return None
    assert count is None
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset"
    )
