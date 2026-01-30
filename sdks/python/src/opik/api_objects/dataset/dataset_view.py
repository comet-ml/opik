"""
DatasetView - A read-only view of a filtered dataset.

This module provides the DatasetView class, which represents a filtered subset
of a dataset. DatasetView inherits from Dataset but prevents remote modification
operations. Users can perform local operations (reading, converting to pandas/json)
or convert the view to a full Dataset using to_dataset().
"""

import json
import logging
from typing import (
    Optional,
    Any,
    List,
    Dict,
    Sequence,
    TYPE_CHECKING,
)

from opik.rest_api import client as rest_api_client
from opik import exceptions
from opik.api_objects import opik_query_language
from .dataset import Dataset

if TYPE_CHECKING:
    import pandas as pd

LOGGER = logging.getLogger(__name__)


class DatasetView(Dataset):
    """
    Read-only view of a filtered dataset.

    This class represents a filtered subset of a dataset. It inherits from Dataset
    but prevents remote modification operations. Users can perform local operations
    (reading, converting to pandas/json) or convert the view to a full Dataset
    using to_dataset().

    DatasetView objects are created by calling `Opik.get_dataset()` with a
    `filter_string` parameter. The filter is applied on the backend, so only
    matching items are returned when streaming or fetching items.

    Note:
        This object should not be created directly. Use :meth:`opik.Opik.get_dataset`
        with a filter_string parameter instead.

    Args:
        name: The name of the source dataset
        description: The description of the source dataset
        rest_client: The REST API client instance
        filter_string: The OQL filter string applied to this view
        dataset_items_count: The number of items matching the filter (optional)

    Example:
        >>> client = opik.Opik()
        >>> # Get a filtered view of the dataset
        >>> failed_tests = client.get_dataset(
        ...     name="my_dataset",
        ...     filter_string="tags contains 'failed'"
        ... )
        >>> # Read operations work normally
        >>> items = failed_tests.get_items()
        >>> df = failed_tests.to_pandas()
        >>> # Mutation operations raise DatasetViewImmutableError
        >>> failed_tests.insert([{"input": "new"}])  # Raises error
        >>> # Convert to a new dataset if you need to modify
        >>> new_dataset = failed_tests.to_dataset(name="fixed_tests")
    """

    def __init__(
        self,
        name: str,
        description: Optional[str],
        rest_client: rest_api_client.OpikApi,
        filter_string: str,
        dataset_items_count: Optional[int] = None,
    ) -> None:
        super().__init__(
            name=name,
            description=description,
            rest_client=rest_client,
            dataset_items_count=dataset_items_count,
        )
        self._filter_string = filter_string

    @property
    def filter_string(self) -> str:
        """The OQL filter string applied to this view."""
        return self._filter_string

    @property
    def dataset_items_count(self) -> Optional[int]:
        """
        Always None for DatasetView as the count is not known before the items are fetched.
        """
        #  Do not fetch the unfiltered dataset count from the backend.
        return self._dataset_items_count

    def __repr__(self) -> str:
        return (
            f"DatasetView(name='{self._name}', "
            f"filter_string='{self._filter_string}')"
        )

    def _raise_read_only_exception(self) -> None:
        """
        Raise DatasetViewImmutableError for mutation operations.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        raise exceptions.DatasetViewImmutableError(
            "Cannot modify items in a DatasetView. "
            "Use to_dataset() to create a mutable dataset from this view."
        )

    # Override mutation methods to raise errors

    def insert(self, items: Sequence[Dict[str, Any]]) -> None:
        """
        Insert is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def update(self, items: List[Dict[str, Any]]) -> None:
        """
        Update is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def delete(self, items_ids: List[str]) -> None:
        """
        Delete is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def clear(self) -> None:
        """
        Clear is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def insert_from_json(
        self,
        json_array: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Insert from JSON is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def read_jsonl_from_file(
        self,
        file_path: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Read JSONL from file is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    def insert_from_pandas(
        self,
        dataframe: "pd.DataFrame",
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Insert from pandas is not supported on DatasetView.

        Raises:
            DatasetViewImmutableError: Always raised - DatasetView is read-only.
        """
        self._raise_read_only_exception()

    # Override filter method to apply the view's filter

    def _get_stream_filters(self) -> Optional[str]:
        """
        Get the filter string to apply when streaming items.

        Parses the OQL filter_string using the existing OpikQueryLanguage parser
        and converts it to JSON format expected by the backend API.

        Returns:
            JSON-encoded filter array for the backend API, or None if no filter.
        """
        if not self._filter_string:
            return None

        oql = opik_query_language.OpikQueryLanguage.for_dataset_items(
            self._filter_string
        )
        filter_expressions = oql.get_filter_expressions()

        if filter_expressions is None:
            return None

        # Convert to backend format - the parser already produces the correct structure
        # The backend expects: [{"field": "tags", "operator": "contains", "value": "mine"}]
        return json.dumps(filter_expressions)

    def to_dataset(
        self,
        name: str,
        description: Optional[str] = None,
    ) -> Dataset:
        """
        Convert this view to a full mutable Dataset by creating it on the backend.

        This method creates a new dataset on the backend and copies all items
        from this filtered view into the new dataset. The resulting Dataset
        object can be modified (insert, update, delete operations).

        Args:
            name: Name for the new dataset. Must be unique within the workspace.
            description: Optional description for the new dataset.

        Returns:
            A new Dataset object containing all items from this filtered view.

        Raises:
            ApiError: If dataset creation fails or a dataset with the same name exists.

        Example:
            >>> # Get filtered view
            >>> failed_tests = client.get_dataset(
            ...     name="my_tests",
            ...     filter_string="tags contains 'failed'"
            ... )
            >>> # Convert to new dataset
            >>> new_dataset = failed_tests.to_dataset(
            ...     name="failed_tests_copy",
            ...     description="Copy of failed test cases"
            ... )
            >>> # Now you can modify the new dataset
            >>> new_dataset.insert([{"input": "new test"}])
        """
        LOGGER.info(
            "Converting DatasetView '%s' (filter: '%s') to new Dataset '%s'",
            self._name,
            self._filter_string,
            name,
        )

        # Create new dataset on backend
        self._rest_client.datasets.create_dataset(name=name, description=description)

        # Create the Dataset object
        new_dataset = Dataset(
            name=name,
            description=description,
            rest_client=self._rest_client,
            dataset_items_count=0,
        )

        # Stream all items from this view and insert into new dataset
        items_to_insert: List[Dict[str, Any]] = []
        for item in self.__internal_api__stream_items_as_dataclasses__():
            # Convert DatasetItem to dict, excluding the id so new IDs are generated
            item_dict = item.get_content()
            items_to_insert.append(item_dict)

        if items_to_insert:
            new_dataset.insert(items_to_insert)
            LOGGER.info(
                "Inserted %d items from DatasetView into new Dataset '%s'",
                len(items_to_insert),
                name,
            )

        return new_dataset
