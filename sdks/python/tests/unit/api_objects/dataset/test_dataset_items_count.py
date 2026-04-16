from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset
from opik.rest_api.types.dataset_public import DatasetPublic
from opik.rest_api.types.dataset_version_public import DatasetVersionPublic


def test_dataset_items_count__cached_value__returns_cached_count():
    """Test that dataset_items_count returns cached value when available."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
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
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    # Access the count
    count = dataset.dataset_items_count

    # Should fetch from backend
    assert count == 10
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once_with(
        dataset_name="test_dataset", project_name="Test project"
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
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
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
        dataset_name="test_dataset", project_name="Test project"
    )


def test_delete__invalidates_cached_count():
    """Test that delete() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
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
        dataset_name="test_dataset", project_name="Test project"
    )


def test_update__invalidates_cached_count():
    """Test that update() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
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
        dataset_name="test_dataset", project_name="Test project"
    )


def test_insert__invalidates_cached_count():
    """Test that insert() invalidates the cached count."""
    mock_rest_client = Mock()

    # Create dataset with initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
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
        dataset_name="test_dataset", project_name="Test project"
    )


def test_backend_returns_none_count__falls_back_to_version_info():
    """Test that if backend returns None for count, falls back to version info."""
    mock_rest_client = Mock()

    # Mock the backend response with None count
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=None)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Mock version info with items_total
    mock_version = DatasetVersionPublic(items_total=42)
    mock_rest_client.datasets.list_dataset_versions.return_value = Mock(
        content=[mock_version]
    )

    # Create dataset without initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count = dataset.dataset_items_count

    assert count == 42
    mock_rest_client.datasets.list_dataset_versions.assert_called_once()


def test_backend_returns_none_count__no_version_info__returns_none():
    """Test that if both backend count and version info are unavailable, returns None."""
    mock_rest_client = Mock()

    # Mock the backend response with None count
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=None)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Mock no version info
    mock_rest_client.datasets.list_dataset_versions.return_value = Mock(content=[])

    # Create dataset without initial count
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count = dataset.dataset_items_count

    assert count is None


def test_backend_returns_none_count__version_fallback_cached():
    """Test that the version info fallback value is cached for subsequent calls."""
    mock_rest_client = Mock()

    # Mock the backend response with None count
    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=None)
    mock_rest_client.datasets.get_dataset_by_identifier.return_value = (
        mock_dataset_public
    )

    # Mock version info with items_total
    mock_version = DatasetVersionPublic(items_total=15)
    mock_rest_client.datasets.list_dataset_versions.return_value = Mock(
        content=[mock_version]
    )

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count1 = dataset.dataset_items_count
    count2 = dataset.dataset_items_count

    assert count1 == 15
    assert count2 == 15
    # Version info called only once, then cached
    mock_rest_client.datasets.list_dataset_versions.assert_called_once()
