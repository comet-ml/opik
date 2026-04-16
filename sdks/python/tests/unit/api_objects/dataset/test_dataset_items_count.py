from unittest.mock import Mock
from opik.api_objects.dataset.dataset import Dataset
from opik.rest_api.types.dataset_public import DatasetPublic


def test_dataset_items_count__cached_value__returns_cached_count():
    """Test that dataset_items_count returns cached value when available."""
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
    )

    count = dataset.dataset_items_count

    assert count == 5
    mock_rest_client.datasets.get_dataset_by_id.assert_not_called()


def test_dataset_items_count__no_cached_value__fetches_from_backend():
    """Test that dataset_items_count fetches from backend when cache is None."""
    mock_rest_client = Mock()

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=10)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count = dataset.dataset_items_count

    assert count == 10
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once_with(id=dataset.id)


def test_dataset_items_count__fetched_once__cached_for_subsequent_calls():
    """Test that dataset_items_count is fetched once and then cached."""
    mock_rest_client = Mock()

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=10)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count1 = dataset.dataset_items_count
    count2 = dataset.dataset_items_count
    count3 = dataset.dataset_items_count

    assert count1 == 10
    assert count2 == 10
    assert count3 == 10
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once()


def test_delete__invalidates_cached_count():
    """Test that delete() invalidates the cached count."""
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
    )

    assert dataset.dataset_items_count == 5

    dataset.delete(["item1", "item2"])

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=3)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    count = dataset.dataset_items_count

    assert count == 3
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once()


def test_update__invalidates_cached_count():
    """Test that update() invalidates the cached count."""
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
    )

    assert dataset.dataset_items_count == 5

    updated_item = {
        "id": "item1",
        "input": {"key": "updated_value"},
        "expected_output": {"key": "updated_output"},
    }
    dataset.update([updated_item])

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=5)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    count = dataset.dataset_items_count

    assert count == 5
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once()


def test_insert__invalidates_cached_count():
    """Test that insert() invalidates the cached count."""
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
    )

    assert dataset.dataset_items_count == 5

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

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=7)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    count = dataset.dataset_items_count

    assert count == 7
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once()


def test_backend_returns_none_count__property_returns_none():
    """Test that if backend returns None for count, property returns None."""
    mock_rest_client = Mock()

    mock_dataset_public = DatasetPublic(name="test_dataset", dataset_items_count=None)
    mock_rest_client.datasets.get_dataset_by_id.return_value = mock_dataset_public

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=None,
    )

    count = dataset.dataset_items_count

    assert count is None
    mock_rest_client.datasets.get_dataset_by_id.assert_called_once()
