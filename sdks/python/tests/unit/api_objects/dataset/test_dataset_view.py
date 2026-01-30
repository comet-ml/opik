"""Tests for DatasetView class - read-only filtered dataset views."""

import json

import pytest
from unittest.mock import Mock, MagicMock

from opik.api_objects.dataset.dataset import Dataset
from opik.api_objects.dataset.dataset_view import DatasetView
from opik import exceptions


class TestDatasetViewImmutability:
    """Tests for DatasetView immutability - all mutation methods should raise errors."""

    def test_insert__should_raise_immutable_error(self):
        """Verify insert raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.insert([{"input": "test"}])

    def test_update__should_raise_immutable_error(self):
        """Verify update raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.update([{"id": "item-1", "input": "test"}])

    def test_delete__should_raise_immutable_error(self):
        """Verify delete raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.delete(["item-1", "item-2"])

    def test_clear__should_raise_immutable_error(self):
        """Verify clear raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.clear()

    def test_insert_from_json__should_raise_immutable_error(self):
        """Verify insert_from_json raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.insert_from_json('[{"input": "test"}]')

    def test_read_jsonl_from_file__should_raise_immutable_error(self):
        """Verify read_jsonl_from_file raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.read_jsonl_from_file("/path/to/file.jsonl")

    def test_insert_from_pandas__should_raise_immutable_error(self):
        """Verify insert_from_pandas raises DatasetViewImmutableError."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        # We don't need actual pandas DataFrame for this test
        mock_dataframe = Mock()
        with pytest.raises(exceptions.DatasetViewImmutableError):
            view.insert_from_pandas(mock_dataframe)


class TestDatasetViewProperties:
    """Tests for DatasetView properties and metadata."""

    def test_filter_string_property__returns_filter(self):
        """Verify filter_string property returns the filter used to create the view."""
        mock_rest_client = Mock()
        filter_str = "tags contains 'failed'"
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string=filter_str,
        )

        assert view.filter_string == filter_str

    def test_repr__includes_filter_string(self):
        """Verify __repr__ includes the filter string."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        repr_str = repr(view)
        assert "test_dataset" in repr_str
        assert "tags contains 'test'" in repr_str
        assert "DatasetView" in repr_str

    def test_name_property__returns_dataset_name(self):
        """Verify name property returns the source dataset name."""
        mock_rest_client = Mock()
        view = DatasetView(
            name="my_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string="tags contains 'test'",
        )

        assert view.name == "my_dataset"


class TestDatasetViewFiltering:
    """Tests for DatasetView filter application."""

    def test_stream_items__passes_filters_to_rest_client(self):
        """Verify streaming items passes the correct JSON-encoded filters to REST client."""
        mock_rest_client = Mock()
        mock_rest_client.datasets.stream_dataset_items = MagicMock(
            return_value=iter([])
        )

        filter_str = 'tags contains "important"'
        view = DatasetView(
            name="test_dataset",
            description="Test description",
            rest_client=mock_rest_client,
            filter_string=filter_str,
        )

        # Trigger streaming to invoke the REST client
        list(view.__internal_api__stream_items_as_dataclasses__())

        # Verify the REST client was called with properly formatted filters
        mock_rest_client.datasets.stream_dataset_items.assert_called()
        call_kwargs = mock_rest_client.datasets.stream_dataset_items.call_args.kwargs
        assert "filters" in call_kwargs
        filters_json = call_kwargs["filters"]
        assert filters_json is not None

        parsed = json.loads(filters_json)
        assert len(parsed) == 1
        assert parsed[0]["field"] == "tags"
        assert parsed[0]["operator"] == "contains"
        assert parsed[0]["value"] == "important"


class TestDatasetViewToDataset:
    """Tests for DatasetView.to_dataset() conversion."""

    def test_to_dataset__creates_new_dataset(self):
        """Verify to_dataset creates a new dataset on the backend."""
        mock_rest_client = Mock()
        mock_rest_client.datasets.stream_dataset_items = MagicMock(
            return_value=iter([])
        )

        view = DatasetView(
            name="source_dataset",
            description="Source description",
            rest_client=mock_rest_client,
            filter_string='tags contains "test"',
        )

        new_dataset = view.to_dataset(
            name="new_dataset",
            description="New description",
        )

        # Verify create_dataset was called
        mock_rest_client.datasets.create_dataset.assert_called_once_with(
            name="new_dataset",
            description="New description",
        )

        # Verify the returned dataset has the correct properties
        assert new_dataset.name == "new_dataset"
        assert new_dataset._description == "New description"

    def test_to_dataset__returns_mutable_dataset(self):
        """Verify to_dataset returns a regular Dataset that can be modified."""
        mock_rest_client = Mock()
        mock_rest_client.datasets.stream_dataset_items = MagicMock(
            return_value=iter([])
        )

        view = DatasetView(
            name="source_dataset",
            description="Source description",
            rest_client=mock_rest_client,
            filter_string='tags contains "test"',
        )

        new_dataset = view.to_dataset(name="new_dataset")

        # Verify the returned object is a Dataset, not DatasetView
        assert isinstance(new_dataset, Dataset)
        assert not isinstance(new_dataset, DatasetView)
