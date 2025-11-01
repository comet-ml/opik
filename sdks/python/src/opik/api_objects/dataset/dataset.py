import logging
import functools
import time
import random
import warnings
from dataclasses import dataclass
from typing import Optional, Any, List, Dict, Sequence, Set, TYPE_CHECKING, Callable

from opik.api_objects import rest_stream_parser
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import dataset_item_write as rest_dataset_item
from opik.rest_api.core.api_error import ApiError
from opik.message_processing.batching import sequence_splitter
from opik.rate_limit import rate_limit
import opik.exceptions as exceptions
import opik.config as config
from opik.rest_client_configurator import retry_decorator
from .. import constants
from . import dataset_item, converters

if TYPE_CHECKING:
    import pandas as pd

LOGGER = logging.getLogger(__name__)


@dataclass
class DatasetSplit:
    """Representation of a dataset split with train/test partitions."""

    train: List[Dict[str, Any]]
    test: List[Dict[str, Any]]

    @property
    def validation(self) -> List[Dict[str, Any]]:
        """Alias for backward compatibility."""
        return self.test


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
    ) -> None:
        """
        A Dataset object. This object should not be created directly, instead use :meth:`opik.Opik.create_dataset` or :meth:`opik.Opik.get_dataset`.
        """
        self._name = name
        self._description = description
        self._rest_client = rest_client

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

    def _insert_batch_with_retry(
        self, batch: List[rest_dataset_item.DatasetItemWrite]
    ) -> None:
        """Insert a batch of dataset items with automatic retry on rate limit errors."""
        _ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.datasets.create_or_update_dataset_items(
                dataset_name=self._name, items=batch
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

        for batch in batches:
            LOGGER.debug("Sending dataset items batch of size %d", len(batch))
            self._insert_batch_with_retry(batch)

    def insert(self, items: Sequence[Dict[str, Any]]) -> None:
        """
        Insert new items into the dataset.

        Args:
            items: List of dicts (which will be converted to dataset items)
                to add to the dataset.
        """
        dataset_items: List[dataset_item.DatasetItem] = [  # type: ignore
            (dataset_item.DatasetItem(**item) if isinstance(item, dict) else item)
            for item in items
        ]
        self.__internal_api__insert_items_as_dataclasses__(dataset_items)

    def __internal_api__sync_hashes__(self) -> None:
        """Updates all the hashes in the dataset"""
        LOGGER.debug("Start hash sync in dataset")
        all_items = self.__internal_api__get_items_as_dataclasses__()

        self._id_to_hash = {}
        self._hashes = set()

        for item in all_items:
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

    def delete(self, items_ids: List[str]) -> None:
        """
        Delete items from the dataset.

        Args:
            items_ids: List of item ids to delete.
        """
        batches = sequence_splitter.split_into_batches(
            items_ids, max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Deleting dataset items batch: %s", batch)
            self._rest_client.datasets.delete_dataset_items(item_ids=batch)

            for item_id in batch:
                if item_id in self._id_to_hash:
                    hash = self._id_to_hash[item_id]
                    self._hashes.discard(hash)
                    del self._id_to_hash[item_id]

    def clear(self) -> None:
        """
        Delete all items from the given dataset.
        """
        all_items = self.__internal_api__get_items_as_dataclasses__()
        item_ids = [item.id for item in all_items if item.id is not None]

        self.delete(item_ids)

    def to_pandas(self) -> "pd.DataFrame":
        """
        Requires: `pandas` library to be installed.

        Convert the dataset to a pandas DataFrame.

        Returns:
            A pandas DataFrame containing all items in the dataset.
        """
        dataset_items = self.__internal_api__get_items_as_dataclasses__()

        return converters.to_pandas(dataset_items, keys_mapping={})

    def to_json(self) -> str:
        """
        Convert the dataset to a JSON string.

        Returns:
            A JSON string representation of all items in the dataset.
        """
        dataset_items = self.__internal_api__get_items_as_dataclasses__()

        return converters.to_json(dataset_items, keys_mapping={})

    def train_test_split(
        self,
        *,
        test_dataset: "Dataset" | None = None,
        test_item_ids: Sequence[str] | None = None,
        split_field: str | None = None,
        train_label: str = "train",
        test_label: str = "test",
        test_size: float | None = None,
        seed: int | None = None,
        limit: int | None = None,
    ) -> DatasetSplit:
        """
        Create a train/test split from the dataset (HuggingFace style).

        Exactly one of the following strategies may be provided: ``test_dataset``,
        ``test_item_ids``, ``split_field`` or ``test_size``. When no strategy
        is set, all items are returned in the training split and the test split is empty.

        Args:
            test_dataset: External dataset that should act as the test split.
            test_item_ids: Explicit item IDs to include in the test split.
            split_field: Field name that stores split labels either on the item or under ``metadata``.
            train_label: Label that represents the training split when ``split_field`` is used.
            test_label: Label representing test items when ``split_field`` is used.
            test_size: Ratio (0 < ratio < 1) of items to allocate to the test split.
            seed: Optional random seed used when ``test_size`` is provided.
            limit: Maximum number of items to retrieve from the dataset(s).

        Returns:
            DatasetSplit: Two lists of dataset items for training and test splits.
        """
        strategies_selected = sum(
            1
            for condition in (
                test_dataset is not None,
                bool(test_item_ids),
                split_field is not None,
                test_size is not None,
            )
            if condition
        )
        if strategies_selected > 1:
            raise ValueError(
                "Only one test split strategy can be provided at a time."
            )

        items = (
            self.get_items(limit)
            if limit is not None
            else self.get_items()
        )
        train_items = list(items)
        test_items: List[Dict[str, Any]] = []

        if test_dataset is not None:
            test_items = (
                test_dataset.get_items(limit)
                if limit is not None
                else test_dataset.get_items()
            )
            return DatasetSplit(
                train=train_items,
                test=list(test_items),
            )

        if test_item_ids:
            test_id_set = set(test_item_ids)
            test_items = [
                item for item in train_items if item.get("id") in test_id_set
            ]
            train_items = [
                item for item in train_items if item.get("id") not in test_id_set
            ]
            return DatasetSplit(train=train_items, test=test_items)

        if split_field:
            train_split: List[Dict[str, Any]] = []
            test_split: List[Dict[str, Any]] = []
            for item in train_items:
                value = self._extract_split_value(item, split_field)
                if value == test_label:
                    test_split.append(item)
                elif value == train_label or value is None:
                    train_split.append(item)
                else:
                    train_split.append(item)
            return DatasetSplit(train=train_split, test=test_split)

        if test_size is not None:
            if not 0 < test_size < 1:
                raise ValueError("test_size must be between 0 and 1 (exclusive).")
            if len(train_items) <= 1:
                return DatasetSplit(train=train_items, test=[])

            rng = random.Random(seed)
            shuffled = train_items[:]
            rng.shuffle(shuffled)
            test_count = max(1, int(round(len(shuffled) * test_size)))
            if test_count >= len(shuffled):
                test_count = len(shuffled) - 1
            test_items = shuffled[:test_count]
            train_items = shuffled[test_count:]
            return DatasetSplit(train=train_items, test=test_items)

        return DatasetSplit(train=train_items, test=test_items)

    def get_split(
        self,
        *,
        validation_dataset: "Dataset" | None = None,
        validation_item_ids: Sequence[str] | None = None,
        split_field: str | None = None,
        train_label: str = "train",
        validation_label: str = "validation",
        validation_ratio: float | None = None,
        seed: int | None = None,
        limit: int | None = None,
    ) -> DatasetSplit:
        """Deprecated compatibility wrapper for ``train_test_split``."""
        warnings.warn(
            "`Dataset.get_split` is deprecated. Use `Dataset.train_test_split` instead.",
            DeprecationWarning,
            stacklevel=2,
        )
        return self.train_test_split(
            test_dataset=validation_dataset,
            test_item_ids=validation_item_ids,
            split_field=split_field,
            train_label=train_label,
            test_label=validation_label,
            test_size=validation_ratio,
            seed=seed,
            limit=limit,
        )

    def get_items(self, nb_samples: Optional[int] = None) -> List[Dict[str, Any]]:
        """
        Retrieve a fixed set number of dataset items dictionaries.

        Args:
            nb_samples: The number of samples to retrieve. If not set - all items are returned.

        Returns:
            A list of dictionaries objects representing the samples.
        """
        dataset_items_as_dataclasses = self.__internal_api__get_items_as_dataclasses__(
            nb_samples
        )
        dataset_items_as_dicts = [
            {"id": item.id, **item.get_content()}
            for item in dataset_items_as_dataclasses
        ]

        return dataset_items_as_dicts

    @staticmethod
    def _extract_split_value(item: Dict[str, Any], key: str) -> Any:
        """Extract a field from an item or its nested metadata."""
        if key in item:
            return item[key]
        metadata = item.get("metadata")
        if isinstance(metadata, dict):
            return metadata.get(key)
        return None

    @retry_decorator.opik_rest_retry
    def __internal_api__get_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
    ) -> List[dataset_item.DatasetItem]:
        results: List[dataset_item.DatasetItem] = []
        last_retrieved_id: Optional[str] = None
        should_retrieve_more_items = True

        dataset_items_ids_left = set(dataset_item_ids) if dataset_item_ids else None

        while should_retrieve_more_items:
            dataset_items = rest_stream_parser.read_and_parse_stream(
                stream=self._rest_client.datasets.stream_dataset_items(
                    dataset_name=self._name,
                    last_retrieved_id=last_retrieved_id,
                ),
                item_class=dataset_item.DatasetItem,
                nb_samples=nb_samples,
            )

            if len(dataset_items) == 0:
                should_retrieve_more_items = False

            for item in dataset_items:
                dataset_item_id = item.id
                last_retrieved_id = dataset_item_id

                if dataset_items_ids_left is not None:
                    if dataset_item_id not in dataset_items_ids_left:
                        continue
                    else:
                        dataset_items_ids_left.remove(dataset_item_id)

                data_item_content = item.get_content().get("data", {})

                reconstructed_item = dataset_item.DatasetItem(
                    id=item.id,
                    trace_id=item.trace_id,
                    span_id=item.span_id,
                    source=item.source,
                    **data_item_content,
                )

                results.append(reconstructed_item)

                # Stop retrieving if we have enough samples
                if nb_samples is not None and len(results) == nb_samples:
                    should_retrieve_more_items = False
                    break

                # Stop retrieving if we found all filtered dataset items
                if (
                    dataset_items_ids_left is not None
                    and len(dataset_items_ids_left) == 0
                ):
                    should_retrieve_more_items = False
                    break

        if dataset_items_ids_left and len(dataset_items_ids_left) > 0:
            LOGGER.warning(
                "The following dataset items were not found in the dataset: %s",
                dataset_items_ids_left,
            )

        return results

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
