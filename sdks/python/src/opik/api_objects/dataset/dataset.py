import abc
import datetime
import logging
import functools
import sys
from typing import (
    Optional,
    Any,
    List,
    Dict,
    Sequence,
    Set,
    TYPE_CHECKING,
    Iterator,
)

if TYPE_CHECKING:
    from opik.api_objects.evaluation_suite import types as evaluation_suite_types

from opik.api_objects import rest_helpers
from opik.rest_api import client as rest_api_client
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import (
    dataset_item_write as rest_dataset_item,
    dataset_version_public,
    evaluator_item_write as rest_evaluator_item,
    execution_policy_write as rest_execution_policy,
)
from opik.message_processing.batching import sequence_splitter
from opik import id_helpers
import opik.exceptions as exceptions
import opik.config as config
from .. import constants
from . import dataset_item, converters, rest_operations

if sys.version_info >= (3, 12):
    from typing import override
else:
    from typing_extensions import override

if TYPE_CHECKING:
    import pandas as pd

LOGGER = logging.getLogger(__name__)


class DatasetExportOperations(abc.ABC):
    """
    Abstract base class providing export operations for dataset items.

    This class defines the common interface for exporting dataset items,
    shared by both Dataset (current state) and DatasetVersion (specific version).
    """

    @abc.abstractmethod
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        """
        Stream dataset items as DatasetItem objects.

        Args:
            nb_samples: Maximum number of items to retrieve.
            batch_size: Maximum number of items to fetch per batch.
            dataset_item_ids: Optional list of specific item IDs to retrieve.
            filter_string: Optional OQL filter string to filter dataset items.

        Yields:
            DatasetItem objects one at a time.
        """
        raise NotImplementedError

    def to_pandas(self) -> "pd.DataFrame":
        """
        Convert the dataset items to a pandas DataFrame.

        Requires the `pandas` library to be installed.

        Returns:
            A pandas DataFrame containing all items.
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())
        return converters.to_pandas(dataset_items, keys_mapping={})

    def to_json(self) -> str:
        """
        Convert the dataset items to a JSON string.

        Returns:
            A JSON string representation of all items.
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())
        return converters.to_json(dataset_items, keys_mapping={})

    def get_items(
        self,
        nb_samples: Optional[int] = None,
        filter_string: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """
        Retrieve dataset items as a list of dictionaries.

        Args:
            nb_samples: Maximum number of items to retrieve. If not set, all items are returned.
            filter_string: Optional OQL filter string to filter dataset items.
                Supports filtering by tags, data fields, metadata, etc.

                Supported columns include:
                - `id`, `source`, `trace_id`, `span_id`: String fields
                - `data`: Dictionary field (use dot notation, e.g., "data.category")
                - `tags`: List field (use "contains" operator)
                - `created_at`, `last_updated_at`: DateTime fields (ISO 8601 format)
                - `created_by`, `last_updated_by`: String fields

                Examples:
                - `tags contains "failed"` - Items with 'failed' tag
                - `data.category = "test"` - Items with specific data field value
                - `created_at >= "2024-01-01T00:00:00Z"` - Items created after date

        Returns:
            A list of dictionaries representing the dataset items.
        """
        dataset_items_as_dicts = [
            {"id": item.id, **item.get_content()}
            for item in self.__internal_api__stream_items_as_dataclasses__(
                nb_samples=nb_samples, filter_string=filter_string
            )
        ]
        return dataset_items_as_dicts

    @abc.abstractmethod
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        Get version information for experiment association.

        Returns:
            DatasetVersionPublic containing version metadata (id, version_name, etc.).
            For Dataset, returns info about the current/latest version, or None if no version exists.
            For DatasetVersion, returns info about this specific version.
        """
        raise NotImplementedError


class DatasetVersion(DatasetExportOperations):
    """
    A read-only view of a specific dataset version.

    This class provides access to dataset items at a specific version point in time.
    It supports reading version metadata and retrieving items, but does not allow
    mutations to the dataset.

    This object should not be created directly. Use :meth:`Dataset.get_dataset_version`
    to obtain an instance.
    """

    def __init__(
        self,
        dataset_name: str,
        dataset_id: str,
        rest_client: rest_api_client.OpikApi,
        version_info: dataset_version_public.DatasetVersionPublic,
    ) -> None:
        self._dataset_name = dataset_name
        self._dataset_id = dataset_id
        self._rest_client = rest_client
        self._version_info = version_info

    @property
    def dataset_name(self) -> str:
        """The name of the dataset this version belongs to."""
        return self._dataset_name

    @property
    def name(self) -> str:
        """The name of the dataset this version belongs to (alias for dataset_name)."""
        return self._dataset_name

    @property
    def dataset_id(self) -> str:
        """The unique identifier of the dataset this version belongs to."""
        return self._dataset_id

    @property
    def id(self) -> str:
        """The unique identifier of the dataset this version belongs to (alias for dataset_id)."""
        return self._dataset_id

    @property
    def version_id(self) -> Optional[str]:
        """The unique identifier of this specific version."""
        return self._version_info.id

    @property
    def dataset_items_count(self) -> Optional[int]:
        """Total number of items in this version (alias for items_total)."""
        return self._version_info.items_total

    @property
    def version_hash(self) -> Optional[str]:
        """The unique hash identifier of this version."""
        return self._version_info.version_hash

    @property
    def version_name(self) -> Optional[str]:
        """The sequential version name (e.g., 'v1', 'v2')."""
        return self._version_info.version_name

    @property
    def tags(self) -> Optional[List[str]]:
        """Tags associated with this version."""
        return self._version_info.tags

    @property
    def is_latest(self) -> Optional[bool]:
        """Whether this is the latest version of the dataset."""
        return self._version_info.is_latest

    @property
    def items_total(self) -> Optional[int]:
        """Total number of items in this version."""
        return self._version_info.items_total

    @property
    def items_added(self) -> Optional[int]:
        """Number of items added since the previous version."""
        return self._version_info.items_added

    @property
    def items_modified(self) -> Optional[int]:
        """Number of items modified since the previous version."""
        return self._version_info.items_modified

    @property
    def items_deleted(self) -> Optional[int]:
        """Number of items deleted since the previous version."""
        return self._version_info.items_deleted

    @property
    def change_description(self) -> Optional[str]:
        """Description of changes in this version."""
        return self._version_info.change_description

    @property
    def created_at(self) -> Optional[datetime.datetime]:
        """Timestamp when this version was created."""
        return self._version_info.created_at

    @property
    def created_by(self) -> Optional[str]:
        """User who created this version."""
        return self._version_info.created_by

    @override
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        return rest_operations.stream_dataset_items(
            rest_client=self._rest_client,
            dataset_name=self._dataset_name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=self._version_info.version_hash,
        )

    @override
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        Get version information for this specific dataset version.

        Returns:
            DatasetVersionPublic containing this version's metadata.
        """
        return self._version_info

    def get_evaluators(self) -> List[Any]:
        """
        Get suite-level evaluators for this dataset version.

        DatasetVersion does not support suite-level evaluators, so this always
        returns an empty list.

        Returns:
            Empty list.
        """
        return []

    def get_execution_policy(self) -> "evaluation_suite_types.ExecutionPolicy":
        """
        Get the execution policy for this dataset version.

        DatasetVersion does not support suite-level execution policy, so this
        returns the default execution policy.

        Returns:
            Default execution policy.
        """
        from opik.api_objects.evaluation_suite import validators

        return validators.DEFAULT_EXECUTION_POLICY.copy()


class Dataset(DatasetExportOperations):
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

        # Temporary storage for suite-level evaluators and execution policy until OPIK-4222/4223 is implemented
        self._evaluators: List[Any] = []
        self._execution_policy: Optional["evaluation_suite_types.ExecutionPolicy"] = (
            None
        )

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

    def set_evaluators(self, evaluators: List[Any]) -> None:
        """
        Set suite-level evaluators for this dataset.

        This is used internally by evaluation suites to store evaluators
        that should be applied when running evaluations on this dataset.

        Args:
            evaluators: List of LLMJudge evaluators.
        """
        self._evaluators = evaluators

    def get_evaluators(self) -> List[Any]:
        """
        Get suite-level evaluators for this dataset.

        Returns:
            List of evaluators set for this dataset, or empty list if none set.
        """
        return self._evaluators

    def set_execution_policy(
        self, execution_policy: "evaluation_suite_types.ExecutionPolicy"
    ) -> None:
        """
        Set the execution policy for this dataset.

        This is used internally by evaluation suites to store execution policy
        that should be applied when running evaluations on this dataset.

        Args:
            execution_policy: Execution policy dict with runs_per_item and pass_threshold.
        """
        self._execution_policy = execution_policy

    def get_execution_policy(self) -> "evaluation_suite_types.ExecutionPolicy":
        """
        Get the execution policy for this dataset.

        Returns:
            Execution policy dict if set, or default execution policy if not set.
        """
        if self._execution_policy is not None:
            return self._execution_policy

        from opik.api_objects.evaluation_suite import validators

        return validators.DEFAULT_EXECUTION_POLICY.copy()

    def get_current_version_name(self) -> Optional[str]:
        """
        Get the current version name of the dataset.

        The version name is fetched from the backend and reflects the latest
        committed version after any mutation operations (insert, update, delete).

        Returns:
            The current version name (e.g., 'v1', 'v2'), or None if no version exists.
        """
        version_info = self.get_version_info()
        return version_info.version_name if version_info else None

    @override
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        Get version information for the current (latest) dataset version.

        Returns:
            DatasetVersionPublic containing the current version's metadata,
            or None if no version exists yet.
        """
        versions_response = None
        try:
            versions_response = self._rest_client.datasets.list_dataset_versions(
                id=self.id,
                page=1,
                size=1,
            )
        except ApiError as e:
            if e.status_code == 403:
                LOGGER.debug(
                    "Versioning is not enabled for datasets get version info returning None"
                )
            else:
                raise
        if not versions_response or not versions_response.content:
            return None
        return versions_response.content[0]

    def _convert_to_rest_item(
        self, item: dataset_item.DatasetItem
    ) -> rest_dataset_item.DatasetItemWrite:
        """Convert a DatasetItem to REST API format.

        Args:
            item: The DatasetItem to convert.

        Returns:
            DatasetItemWrite object ready for REST API.
        """
        evaluators = None
        if item.evaluators:
            evaluators = [
                rest_evaluator_item.EvaluatorItemWrite(
                    name=e.name,
                    type=e.type,  # type: ignore
                    config=e.config,
                )
                for e in item.evaluators
            ]

        execution_policy = None
        if item.execution_policy:
            execution_policy = rest_execution_policy.ExecutionPolicyWrite(
                runs_per_item=item.execution_policy.runs_per_item,
                pass_threshold=item.execution_policy.pass_threshold,
            )

        return rest_dataset_item.DatasetItemWrite(
            id=item.id,  # type: ignore
            trace_id=item.trace_id,  # type: ignore
            span_id=item.span_id,  # type: ignore
            source=item.source,  # type: ignore
            data=item.get_content(),
            evaluators=evaluators,
            execution_policy=execution_policy,
        )

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
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
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

        rest_items = [self._convert_to_rest_item(item) for item in deduplicated_items]

        batches = sequence_splitter.split_into_batches(
            rest_items,
            max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
            max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
        )

        batch_group_id = id_helpers.generate_id()

        for batch in batches:
            LOGGER.debug("Sending dataset items batch of size %d", len(batch))
            self._insert_batch_with_retry(batch, batch_group_id=batch_group_id)

        # Invalidate the cached count so it will be fetched from backend on next access
        self._dataset_items_count = None

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
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
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

    @override
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
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
            filter_string: Optional OQL filter string to filter dataset items.

        Yields:
            DatasetItem objects one at a time
        """
        return rest_operations.stream_dataset_items(
            rest_client=self._rest_client,
            dataset_name=self._name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=None,
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

    def get_version_view(self, version_name: str) -> DatasetVersion:
        """
        Get a read-only view of a specific dataset version.

        The returned DatasetVersion object allows reading version metadata and
        retrieving items via :meth:`DatasetVersion.get_items`, but does not support
        mutations.

        Args:
            version_name: The version name (e.g., 'v1', 'v2').

        Returns:
            A read-only DatasetVersion object for accessing the specified version.

        Raises:
            opik.exceptions.DatasetVersionNotFound: If the specified version does not exist.

        Example:
            >>> dataset = client.get_dataset("my_dataset")
            >>> version = dataset.get_version_view("v1")
            >>> items = version.get_items()
        """
        version_info = rest_operations.find_version_by_name(
            rest_client=self._rest_client,
            dataset_id=self.id,
            version_name=version_name,
        )

        if version_info is None:
            raise exceptions.DatasetVersionNotFound(
                f"Dataset version '{version_name}' not found in dataset '{self._name}'"
            )

        return DatasetVersion(
            dataset_name=self._name,
            dataset_id=self.id,
            rest_client=self._rest_client,
            version_info=version_info,
        )
