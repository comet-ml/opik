from unittest.mock import Mock

import pytest

import opik.config as config
from opik.api_objects.dataset.dataset import Dataset

from .upload_capture import UploadCapture
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
    capture = UploadCapture()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        rest_httpx_client=capture,
        url_override=capture.base_url,
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
    capture = UploadCapture()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        rest_httpx_client=capture,
        url_override=capture.base_url,
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


def test_from_public__response_carries_id__id_returned_without_a_lookup():
    """The get-dataset response already holds the id, so reads must not pay a
    second by-name lookup for it."""
    mock_rest_client = Mock()
    mock_dataset_public = DatasetPublic(
        id="01a06bd3-f379-7231-a8ff-842808c8ba38",
        name="test_dataset",
        dataset_items_count=7,
    )

    dataset = Dataset.from_public(
        dataset_fern=mock_dataset_public,
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    assert dataset.id == "01a06bd3-f379-7231-a8ff-842808c8ba38"
    mock_rest_client.datasets.get_dataset_by_identifier.assert_not_called()


def test_from_public__response_without_id__falls_back_to_the_lookup():
    mock_rest_client = Mock()
    mock_rest_client.datasets.get_dataset_by_identifier.return_value.id = "looked-up"
    mock_dataset_public = DatasetPublic(name="test_dataset")

    dataset = Dataset.from_public(
        dataset_fern=mock_dataset_public,
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    assert dataset.id == "looked-up"
    mock_rest_client.datasets.get_dataset_by_identifier.assert_called_once()


class _FailAfterFirstRequest(UploadCapture):
    """Accepts the first request, then dies -- a partial upload."""

    def request(self, method, url, **kwargs):
        response = super().request(method, url, **kwargs)
        if self.request_count >= 2:
            raise RuntimeError("connection lost mid-upload")
        return response


@pytest.mark.parametrize("streaming", [True, False])
def test_insert__upload_fails_after_earlier_items_landed__still_invalidates_count(
    streaming,
):
    """A partial insert has changed the dataset, so the cached count is stale too.

    Nothing is rolled back when an upload fails part-way, so the items in the
    requests that did succeed are on the backend. Leaving the count cached means
    `dataset_items_count` keeps reporting the number from before an insert that
    demonstrably added items -- and reports it indefinitely, since the cache is
    only refilled once it has been cleared.

    Both upload paths are covered: a `Dataset` with no HTTP client of its own
    uploads through the generated REST client and has the same obligation.
    """
    mock_rest_client = Mock()
    capture = _FailAfterFirstRequest()

    if streaming:
        transport = {
            "rest_httpx_client": capture,
            "url_override": capture.base_url,
        }
    else:
        transport = {}
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = [
            None,
            RuntimeError("connection lost mid-upload"),
        ]

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
        dataset_items_count=5,
        **transport,
    )
    assert dataset.dataset_items_count == 5

    original_max_batch_size_MB = config.MAX_BATCH_SIZE_MB
    config.MAX_BATCH_SIZE_MB = 1e-9  # one item per request, so the failure is partial
    try:
        with pytest.raises(RuntimeError):
            dataset.insert(
                [{"input": {"key": f"value{i}"}} for i in range(4)], num_threads=1
            )
    finally:
        config.MAX_BATCH_SIZE_MB = original_max_batch_size_MB

    assert dataset._dataset_items_count is None, (
        "A failed insert that persisted earlier items must clear the cached count"
    )
