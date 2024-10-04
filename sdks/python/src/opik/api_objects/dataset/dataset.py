import logging
import json
from typing import Optional, Any, List, Dict, Union, Sequence

from opik.rest_api import client as rest_api_client
from opik.rest_api.types import dataset_item as rest_dataset_item
from opik import exceptions

from .. import helpers, constants
from . import dataset_item, converters, utils
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
        self._hash_to_id: Dict[str, str] = {}
        self._id_to_hash: Dict[str, str] = {}

    @property
    def name(self) -> str:
        """The name of the dataset."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the dataset."""
        return self._description

    def insert(
        self, items: Sequence[Union[dataset_item.DatasetItem, Dict[str, Any]]]
    ) -> None:
        """
        Insert new items into the dataset.

        Args:
            items: List of DatasetItem objects or dicts (which will be converted to DatasetItem objects)
                to add to the dataset.
        """
        items: List[dataset_item.DatasetItem] = [  # type: ignore
            (dataset_item.DatasetItem(**item) if isinstance(item, dict) else item)
            for item in items
        ]

        # Remove duplicates if they already exist
        deduplicated_items = []
        for item in items:
            item_hash = utils.compute_content_hash(item)

            if item_hash in self._hash_to_id:
                if item.id is None or self._hash_to_id[item_hash] == item.id:  # type: ignore
                    LOGGER.debug(
                        "Duplicate item found with hash: %s - ignored the event",
                        item_hash,
                    )
                    continue

            deduplicated_items.append(item)
            self._hash_to_id[item_hash] = item.id  # type: ignore
            self._id_to_hash[item.id] = item_hash  # type: ignore

        rest_items = [
            rest_dataset_item.DatasetItem(
                id=item.id if item.id is not None else helpers.generate_id(),  # type: ignore
                input=item.input,  # type: ignore
                expected_output=item.expected_output,  # type: ignore
                metadata=item.metadata,  # type: ignore
                trace_id=item.trace_id,  # type: ignore
                span_id=item.span_id,  # type: ignore
                source=item.source,  # type: ignore
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

    def _sync_hashes(self) -> None:
        """Updates all the hashes in the dataset"""
        LOGGER.debug("Start hash sync in dataset")
        all_items = self.get_all_items()

        self._hash_to_id = {}
        self._id_to_hash = {}

        for item in all_items:
            item_hash = utils.compute_content_hash(item)
            self._hash_to_id[item_hash] = item.id  # type: ignore
            self._id_to_hash[item.id] = item_hash  # type: ignore

        LOGGER.debug("Finish hash sync in dataset")

    def update(self, items: List[dataset_item.DatasetItem]) -> None:
        """
        Update existing items in the dataset.

        Args:
            items: List of DatasetItem objects to update in the dataset. You need to provide the full item object as it will override what has been supplied previously.

        Raises:
            DatasetItemUpdateOperationRequiresItemId: If any item in the list is missing an id.
        """
        for item in items:
            if item.id is None:  # TODO: implement proper exception
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
                    del self._id_to_hash[item_id]
                    del self._hash_to_id[hash]

    def clear(self) -> None:
        """
        Delete all items from the given dataset.
        """
        all_items = self.get_all_items()
        item_ids = [item.id for item in all_items if item.id is not None]

        self.delete(item_ids)

    def to_pandas(self) -> pandas.DataFrame:
        """
        Convert the dataset to a pandas DataFrame.

        Returns:
            A pandas DataFrame containing all items in the dataset.
        """
        dataset_items = self.get_all_items()

        return converters.to_pandas(dataset_items, keys_mapping={})

    def to_json(self) -> str:
        """
        Convert the dataset to a JSON string.

        Returns:
            A JSON string representation of all items in the dataset.
        """
        dataset_items = self.get_all_items()

        return converters.to_json(dataset_items, keys_mapping={})

    def get_all_items(self) -> List[dataset_item.DatasetItem]:
        """
        Retrieve all items from the dataset.

        Returns:
            A list of DatasetItem objects representing all items in the dataset.
        """
        results: List[dataset_item.DatasetItem] = []

        while True:
            stream = self._rest_client.datasets.stream_dataset_items(
                dataset_name=self._name,
                last_retrieved_id=results[-1].id if len(results) > 0 else None,
            )

            item_bytes = b"".join(stream)
            stream_results: List[dataset_item.DatasetItem] = []
            for line in item_bytes.split(b"\n"):
                if len(line) == 0:
                    continue

                item_content: Dict[str, Any] = json.loads(line.decode("utf-8").strip())

                item = dataset_item.DatasetItem(
                    id=item_content.get("id"),  # type: ignore
                    input=item_content.get("input"),  # type: ignore
                    expected_output=item_content.get("expected_output"),  # type: ignore
                    metadata=item_content.get("metadata"),  # type: ignore
                    trace_id=item_content.get("trace_id"),  # type: ignore
                    span_id=item_content.get("span_id"),  # type: ignore
                    source=item_content.get("source"),  # type: ignore
                )

                stream_results.append(item)

            if len(stream_results) == 0:
                break

            results.extend(stream_results)

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
