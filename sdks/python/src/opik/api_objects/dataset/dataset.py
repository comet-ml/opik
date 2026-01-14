import logging
import functools
import time
from typing import (
    Optional,
    Any,
    List,
    Dict,
    Sequence,
    Set,
    TYPE_CHECKING,
    Callable,
    Iterator,
)

from opik.api_objects import rest_stream_parser
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import (
    dataset_item_write as rest_dataset_item,
    dataset_item as rest_dataset_item_read,
)
from opik.rest_api.core.api_error import ApiError
from opik.message_processing.batching import sequence_splitter
from opik.rate_limit import rate_limit
from opik import id_helpers
import opik.exceptions as exceptions
import opik.config as config
from opik.rest_client_configurator import retry_decorator
from .. import constants
from . import dataset_item, converters

if TYPE_CHECKING:
    import pandas as pd

LOGGER = logging.getLogger(__name__)


def _ensure_rest_api_call_respecting_rate_limit(
    rest_callable: Callable[[], Any],
) -> Any:
    """
    Execute a REST API call with automatic retry on rate limit (429) errors.

    This function handles HTTP 429 rate limit errors by waiting for the duration
    specified in the response headers and retrying the request. Regular retries
    for other errors are handled by the underlying rest client.

    Args:
        rest_callable: A callable that performs the REST API call.

    Returns:
        The result of the successful REST API call.

    Raises:
        ApiError: If the error is not a 429 rate limit error.
    """
    while True:
        try:
            result = rest_callable()
            return result
        except ApiError as exception:
            if exception.status_code == 429:
                # Parse rate limit headers to get retry delay
                if exception.headers is not None:
                    rate_limiter = rate_limit.parse_rate_limit(exception.headers)
                    if rate_limiter is not None:
                        retry_after = rate_limiter.retry_after()
                        LOGGER.info(
                            "Rate limited (HTTP 429), retrying in %s seconds",
                            retry_after,
                        )
                        time.sleep(retry_after)
                        continue

                # Fallback: wait 1 second if no header available
                LOGGER.info(
                    "Rate limited (HTTP 429) with no retry-after header, retrying in 1 second"
                )
                time.sleep(1)
                continue

            # Re-raise if not a 429 error
            raise


class Dataset:
    def __init__(
        self,
        name: str,
        description: Optional[str],
        rest_client: rest_api_client.OpikApi,
        dataset_items_count: Optional[int] = None,
    ) -> None:
        """
        A Dataset object. This object should not be created directly, instead use :meth:`opik.Opik.create_dataset` or :meth:`opik.Opik.get_dataset`.
        """
        self._name = name
        self._description = description
        self._rest_client = rest_client
        self._dataset_items_count = dataset_items_count

        self._id_to_hash: Dict[str, str] = {}
        self._hashes: Set[str] = set()

    @functools.cached_property
    def id(self) -> str:
        """The id of the dataset"""
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._name
        ).id

    @property
    def name(self) -> str:
        """The name of the dataset."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the dataset."""
        return self._description

    @property
    def dataset_items_count(self) -> Optional[int]:
        """
        The total number of items in the dataset.

        If the count is not cached locally, it will be fetched from the backend.
        """
        if self._dataset_items_count is None:
            dataset_info = self._rest_client.datasets.get_dataset_by_identifier(
                dataset_name=self._name
            )
            self._dataset_items_count = dataset_info.dataset_items_count
        return self._dataset_items_count

    def _insert_batch_with_retry(
        self,
        batch: List[rest_dataset_item.DatasetItemWrite],
        batch_group_id: str,
    ) -> None:
        """Insert a batch of dataset items with automatic retry on rate limit errors.

        Args:
            batch: List of dataset items to insert.
            batch_group_id: UUIDv7 identifier that groups all batches from a single
                user operation together. All batches sent as part of one insert/update
                call share the same batch_group_id.
        """
        _ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.datasets.create_or_update_dataset_items(
                dataset_name=self._name, items=batch, batch_group_id=batch_group_id
            )
        )
        LOGGER.debug("Successfully sent dataset items batch of size %d", len(batch))

    def __internal_api__insert_items_as_dataclasses__(
        self, items: List[dataset_item.DatasetItem]
    ) -> None:
        # Remove duplicates if they already exist
        deduplicated_items: List[dataset_item.DatasetItem] = []
        for item in items:
            item_hash = item.content_hash()

            if item_hash in self._hashes:
                LOGGER.debug(
                    "Duplicate item found with hash: %s - ignored the event",
                    item_hash,
                )
                continue

            deduplicated_items.append(item)
            self._hashes.add(item_hash)
            self._id_to_hash[item.id] = item_hash

        rest_items = [
            rest_dataset_item.DatasetItemWrite(
                id=item.id,  # type: ignore
                trace_id=item.trace_id,  # type: ignore
                span_id=item.span_id,  # type: ignore
                source=item.source,  # type: ignore
                data=item.get_content(),
            )
            for item in deduplicated_items
        ]

        batches = sequence_splitter.split_into_batches(
            rest_items,
            max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
            max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
        )

        batch_group_id = id_helpers.generate_id()

        for batch in batches:
            LOGGER.debug("Sending dataset items batch of size %d", len(batch))
            self._insert_batch_with_retry(batch, batch_group_id=batch_group_id)

    def insert(self, items: Sequence[Dict[str, Any]]) -> None:
        """
        Insert new items into the dataset. A new dataset version will be created.

        Args:
            items: List of dicts (which will be converted to dataset items)
                to add to the dataset.
        """
        dataset_items: List[dataset_item.DatasetItem] = [  # type: ignore
            (dataset_item.DatasetItem(**item) if isinstance(item, dict) else item)
            for item in items
        ]
        self.__internal_api__insert_items_as_dataclasses__(dataset_items)

        # Invalidate the cached count so it will be fetched from backend on next access
        self._dataset_items_count = None

    def __internal_api__sync_hashes__(self) -> None:
        """Updates all the hashes in the dataset"""
        LOGGER.debug("Start hash sync in dataset")

        self._id_to_hash = {}
        self._hashes = set()

        for item in self.__internal_api__stream_items_as_dataclasses__():
            item_hash = item.content_hash()
            self._id_to_hash[item.id] = item_hash  # type: ignore
            self._hashes.add(item_hash)

        LOGGER.debug("Finish hash sync in dataset")

    def update(self, items: List[Dict[str, Any]]) -> None:
        """
        Update existing items in the dataset.

        Args:
            items: List of DatasetItem objects to update in the dataset. You need to provide the full item object as it will override what has been supplied previously.

        Raises:
            DatasetItemUpdateOperationRequiresItemId: If any item in the list is missing an id.
        """
        for item in items:
            if "id" not in item:
                raise exceptions.DatasetItemUpdateOperationRequiresItemId(
                    "Missing id for dataset item to update: %s", item
                )

        self.insert(items)

    def _delete_batch_with_retry(
        self,
        batch: List[str],
        batch_group_id: str,
    ) -> None:
        """Delete a batch of dataset items with automatic retry on rate limit errors.

        Args:
            batch: List of item IDs to delete.
            batch_group_id: UUIDv7 identifier that groups all batches from a single
                user operation together. All batches sent as part of one delete
                call share the same batch_group_id.
        """
        _ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.datasets.delete_dataset_items(
                item_ids=batch, batch_group_id=batch_group_id
            )
        )
        LOGGER.debug("Successfully deleted dataset items batch of size %d", len(batch))

    def delete(self, items_ids: List[str]) -> None:
        """
        Delete items from the dataset. A new dataset version will be created.

        Args:
            items_ids: List of item ids to delete.
        """
        batches = sequence_splitter.split_into_batches(
            items_ids, max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE
        )

        batch_group_id = id_helpers.generate_id()

        for batch in batches:
            LOGGER.debug("Deleting dataset items batch: %s", batch)
            self._delete_batch_with_retry(batch, batch_group_id=batch_group_id)

            for item_id in batch:
                if item_id in self._id_to_hash:
                    hash = self._id_to_hash[item_id]
                    self._hashes.discard(hash)
                    del self._id_to_hash[item_id]

        # Invalidate the cached count so it will be fetched from backend on next access
        self._dataset_items_count = None

    def clear(self) -> None:
        """
        Delete all items from the given dataset. A new dataset version will be created.
        """
        item_ids = [
            item.id
            for item in self.__internal_api__stream_items_as_dataclasses__()
            if item.id is not None
        ]

        self.delete(item_ids)

    def to_pandas(self) -> "pd.DataFrame":
        """
        Requires: `pandas` library to be installed.

        Convert the dataset to a pandas DataFrame.

        Returns:
            A pandas DataFrame containing all items in the dataset.
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())

        return converters.to_pandas(dataset_items, keys_mapping={})

    def to_json(self) -> str:
        """
        Convert the dataset to a JSON string.

        Returns:
            A JSON string representation of all items in the dataset.
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())

        return converters.to_json(dataset_items, keys_mapping={})

    def get_items(self, nb_samples: Optional[int] = None) -> List[Dict[str, Any]]:
        """
        Retrieve a fixed set number of dataset items dictionaries.

        Args:
            nb_samples: The number of samples to retrieve. If not set - all items are returned.

        Returns:
            A list of dictionaries objects representing the samples.
        """
        dataset_items_as_dicts = [
            {"id": item.id, **item.get_content()}
            for item in self.__internal_api__stream_items_as_dataclasses__(nb_samples)
        ]

        return dataset_items_as_dicts

    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        """
        Stream dataset items as a generator instead of loading all at once.

        This method yields dataset items one at a time, enabling evaluation to start
        processing items before the entire dataset is downloaded. This is particularly
        useful for large datasets with heavy payloads (images, videos, audio).

        Args:
            nb_samples: Maximum number of items to retrieve. If None, all items are streamed.
            batch_size: Maximum number of items to fetch per batch from the backend.
                        If None, uses the default value from constants.DATASET_STREAM_BATCH_SIZE.
            dataset_item_ids: Optional list of specific item IDs to retrieve. If provided,
                            only items with matching IDs will be yielded.

        Yields:
            DatasetItem objects one at a time
        """
        if batch_size is None:
            batch_size = constants.DATASET_STREAM_BATCH_SIZE

        last_retrieved_id: Optional[str] = None
        should_retrieve_more_items = True
        items_yielded = 0
        dataset_items_ids_left = set(dataset_item_ids) if dataset_item_ids else None

        while should_retrieve_more_items:
            # Wrap the streaming call in retry logic so we can resume from last_retrieved_id
            @retry_decorator.opik_rest_retry
            def _fetch_batch() -> List[rest_dataset_item_read.DatasetItem]:
                return rest_stream_parser.read_and_parse_stream(
                    stream=self._rest_client.datasets.stream_dataset_items(
                        dataset_name=self._name,
                        last_retrieved_id=last_retrieved_id,
                        steam_limit=batch_size,
                    ),
                    item_class=rest_dataset_item_read.DatasetItem,
                    nb_samples=nb_samples,
                )

            dataset_items = _fetch_batch()

            if len(dataset_items) == 0:
                should_retrieve_more_items = False
                break

            for item in dataset_items:
                dataset_item_id = item.id
                last_retrieved_id = dataset_item_id

                # Filter by dataset_item_ids if provided
                if dataset_items_ids_left is not None:
                    if dataset_item_id not in dataset_items_ids_left:
                        continue
                    else:
                        dataset_items_ids_left.remove(dataset_item_id)

                reconstructed_item = dataset_item.DatasetItem(
                    id=item.id,
                    trace_id=item.trace_id,
                    span_id=item.span_id,
                    source=item.source,
                    **item.data,
                )

                yield reconstructed_item
                items_yielded += 1

                # Stop retrieving if we have enough samples
                if nb_samples is not None and items_yielded >= nb_samples:
                    should_retrieve_more_items = False
                    break

                # Stop retrieving if we found all filtered dataset items
                if (
                    dataset_items_ids_left is not None
                    and len(dataset_items_ids_left) == 0
                ):
                    should_retrieve_more_items = False
                    break

        # Warn if some requested items were not found
        if dataset_items_ids_left and len(dataset_items_ids_left) > 0:
            LOGGER.warning(
                "The following dataset items were not found in the dataset: %s",
                dataset_items_ids_left,
            )

    def insert_from_json(
        self,
        json_array: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Args:
            json_array: json string of format: "[{...}, {...}, {...}]" where every dictionary
                is to be transformed into dataset item
            keys_mapping: dictionary that maps json keys to item fields names
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your json dicts contain keys that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_json(
            json_array, keys_mapping=keys_mapping, ignore_keys=ignore_keys
        )

        self.insert(new_items)

    def read_jsonl_from_file(
        self,
        file_path: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Read JSONL from a file and insert it into the dataset.

        Args:
            file_path: Path to the JSONL file
            keys_mapping: dictionary that maps json keys to item fields names
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your json dicts contain keys that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys
        new_items = converters.from_jsonl_file(file_path, keys_mapping, ignore_keys)
        self.insert(new_items)

    def insert_from_pandas(
        self,
        dataframe: "pd.DataFrame",
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Requires: `pandas` library to be installed.

        Args:
            dataframe: pandas dataframe
            keys_mapping: Dictionary that maps dataframe column names to dataset item field names.
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your dataframe contains columns that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_pandas(dataframe, keys_mapping, ignore_keys)

        self.insert(new_items)
