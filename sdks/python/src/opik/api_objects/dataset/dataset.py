import logging
import json
from typing import Optional, Any, List, Dict, Sequence, Set

from opik.rest_api import client as rest_api_client
from opik.rest_api.types import dataset_item as rest_dataset_item
from opik import exceptions

from .. import helpers, constants
from . import dataset_item, converters
import pandas

LOGGER = logging.getLogger(__name__)


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

    @property
    def name(self) -> str:
        """The name of the dataset."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the dataset."""
        return self._description

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
            rest_dataset_item.DatasetItem(
                id=item.id,  # type: ignore
                trace_id=item.trace_id,  # type: ignore
                span_id=item.span_id,  # type: ignore
                source=item.source,  # type: ignore
                data=item.get_content(),
            )
            for item in deduplicated_items
        ]

        batches = helpers.list_to_batches(
            rest_items, batch_size=constants.DATASET_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Sending dataset items batch: %s", batch)
            self._rest_client.datasets.create_or_update_dataset_items(
                dataset_name=self._name, items=batch
            )

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
        batches = helpers.list_to_batches(
            items_ids, batch_size=constants.DATASET_ITEMS_MAX_BATCH_SIZE
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

    def to_pandas(self) -> pandas.DataFrame:
        """
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

    def get_items(self, nb_samples: Optional[int] = None) -> List[Dict[str, Any]]:
        """
        Retrieve a fixed set number of dataset items dictionaries.

        Args:
            nb_samples: The number of samples to retrieve. If not set - all items are returned.

        Returns:
            A list of dictionries objects representing the samples.
        """
        dataset_items_as_dataclasses = self.__internal_api__get_items_as_dataclasses__(
            nb_samples
        )
        dataset_items_as_dicts = [
            {"id": item.id, **item.get_content()}
            for item in dataset_items_as_dataclasses
        ]

        return dataset_items_as_dicts

    def __internal_api__get_items_as_dataclasses__(
        self, nb_samples: Optional[int] = None
    ) -> List[dataset_item.DatasetItem]:
        results: List[dataset_item.DatasetItem] = []

        while True:
            stream = self._rest_client.datasets.stream_dataset_items(
                dataset_name=self._name,
                last_retrieved_id=results[-1].id if len(results) > 0 else None,
            )

            previous_results_size = len(results)
            if nb_samples is not None and len(results) == nb_samples:
                break

            item_bytes = b"".join(stream)
            for line in item_bytes.split(b"\n"):
                if len(line) == 0:
                    continue

                full_item_content: Dict[str, Any] = json.loads(
                    line.decode("utf-8").strip()
                )
                data_item_content = (
                    full_item_content["data"] if "data" in full_item_content else {}
                )

                item = dataset_item.DatasetItem(
                    id=full_item_content.get("id"),  # type: ignore
                    trace_id=full_item_content.get("trace_id"),  # type: ignore
                    span_id=full_item_content.get("span_id"),  # type: ignore
                    source=full_item_content.get("source"),  # type: ignore
                    **data_item_content,
                )

                results.append(item)

                # Break the loop if we have enough samples
                if nb_samples is not None and len(results) == nb_samples:
                    break

            # Break the loop if we have not received any new samples
            if len(results) == previous_results_size:
                break

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
        dataframe: pandas.DataFrame,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
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
