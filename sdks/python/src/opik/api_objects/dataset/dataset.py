import abc
import collections.abc
import datetime
import logging
import os

import httpx
import functools
import sys
from typing import (
    Any,
    Dict,
    Iterable,
    Iterator,
    List,
    Optional,
    Set,
    TYPE_CHECKING,
    Tuple,
    Union,
)

from opik.api_objects import rest_helpers
from opik.rest_client_configurator import retry_decorator
from opik.rest_api import client as rest_api_client
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import (
    dataset_public as rest_dataset_public,
    dataset_version_public,
)
from opik.message_processing.batching import sequence_splitter
from opik import httpx_client, id_helpers, semantic_version
import opik.exceptions as exceptions
import opik.config as config
from .. import constants
from . import (
    dataset_item,
    converters,
    rest_operations,
    execution_policy,
    streaming_writer,
)

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

    @abc.abstractmethod
    def __internal_api__stream_item_chunks__(
        self,
        chunk_size: int,
        num_threads: int,
        nb_samples: Optional[int],
        filter_string: Optional[str],
    ) -> Iterator[List[Dict[str, Any]]]:
        """
        Stream dataset items as chunks of raw dictionaries.

        Args:
            chunk_size: Number of items per chunk.
            num_threads: Number of chunks fetched concurrently.
            nb_samples: Maximum number of items to retrieve.
            filter_string: Optional OQL filter string to filter dataset items.

        Yields:
            Lists of dictionaries representing the dataset items.
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
        num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
        chunk_size: int = constants.DATASET_STREAM_BATCH_SIZE,
    ) -> List[Dict[str, Any]]:
        """
        Retrieve dataset items as a list of dictionaries.

        Args:
            nb_samples: Maximum number of items to retrieve. Must be a positive
                integer; omit it or pass ``None`` to return all items. Zero and
                negative values raise rather than being treated as a limit.
            num_threads: Number of item pages fetched concurrently. Must be a
                positive integer, defaults to 4; pass ``1`` to fetch
                sequentially. Raising it speeds up large reads at the cost of
                more load on the backend. Capped at
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``. Use
                :meth:`stream_items` instead when the dataset is too large to
                hold in memory all at once.
            chunk_size: Number of items fetched per request. See
                :meth:`stream_items` for how to pick it; the whole result is
                materialized either way, so this only trades request count
                against per-request size.
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

        Raises:
            ValueError: If ``num_threads`` is not a positive integer, if
                ``chunk_size`` is not a positive integer or exceeds
                ``constants.DATASET_ITEMS_READ_MAX_CHUNK_SIZE``, or if
                ``nb_samples`` is not a positive integer.
        """
        return [
            item
            for chunk in self.stream_items(
                chunk_size=chunk_size,
                filter_string=filter_string,
                nb_samples=nb_samples,
                num_threads=num_threads,
            )
            for item in chunk
        ]

    def stream_items(
        self,
        chunk_size: int = constants.DATASET_STREAM_BATCH_SIZE,
        num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
        filter_string: Optional[str] = None,
        nb_samples: Optional[int] = None,
    ) -> Iterator[List[Dict[str, Any]]]:
        """
        Read dataset items in chunks, fetching the chunks concurrently.

        The chunked counterpart to :meth:`get_items`, which is itself built on
        this method: chunks are fetched in parallel and are handed back as
        plain dictionaries without going through the typed REST layer. Prefer
        it over :meth:`get_items` when you want to start processing before the
        whole dataset has been downloaded, or when the dataset is too large to
        hold in memory all at once.

        Items have exactly the shape :meth:`get_items` returns: the item's
        data plus its ``id``.

        The read is pinned to a single dataset version, so items inserted or
        deleted while it is in progress do not affect it. On backends where
        dataset versioning is unavailable there is no version to pin to and the
        live state is read instead; a concurrent insert can then shift the
        remaining pages, returning one item twice and skipping another. Read a
        :class:`DatasetVersion` explicitly if you need that guarantee there.

        Args:
            chunk_size: Number of items per chunk, defaulting to and capped at
                the same batch size the typed item stream reads with
                (``constants.DATASET_STREAM_BATCH_SIZE``). Fetching a chunk
                costs a fixed overhead whatever its size, so lowering this
                makes the whole read slower; lower it when the items are
                individually large, bearing in mind that up to
                ``2 * num_threads`` chunks are held in memory at once.
            num_threads: Number of chunks fetched concurrently. Must be a
                positive integer, defaults to 4; pass ``1`` to fetch
                sequentially. Capped at
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``.
            filter_string: Optional OQL filter string to filter dataset items.
                Accepts the same expressions as :meth:`get_items`.
            nb_samples: Maximum number of items to read. Must be a positive
                integer; omit it or pass ``None`` to read the whole dataset.
                Zero and negative values raise rather than being treated as a
                limit.

        Yields:
            Lists of dictionaries representing the dataset items, in dataset
            order. The last chunk may be shorter than ``chunk_size``; empty
            chunks are never yielded.

        Raises:
            ValueError: If ``num_threads`` is not a positive integer, if
                ``chunk_size`` is not a positive integer or exceeds
                ``constants.DATASET_ITEMS_READ_MAX_CHUNK_SIZE``, or if
                ``nb_samples`` is not a positive integer.

        Example:
            >>> for chunk in dataset.stream_items(chunk_size=2000, num_threads=8):
            ...     process(chunk)

        Note:
            ``nb_samples`` items are read starting from the beginning of the
            dataset, so the same call reads the same items whatever the thread
            count.
        """
        if isinstance(chunk_size, bool) or not isinstance(chunk_size, int):
            raise ValueError("chunk_size must be a positive integer")
        if chunk_size < 1:
            raise ValueError("chunk_size must be a positive integer")
        if chunk_size > constants.DATASET_ITEMS_READ_MAX_CHUNK_SIZE:
            raise ValueError(
                "chunk_size must not exceed "
                f"{constants.DATASET_ITEMS_READ_MAX_CHUNK_SIZE}, got {chunk_size}"
            )
        if isinstance(num_threads, bool) or not isinstance(num_threads, int):
            raise ValueError("num_threads must be a positive integer")
        if num_threads < 1:
            raise ValueError("num_threads must be a positive integer")
        if nb_samples is not None and (
            isinstance(nb_samples, bool)
            or not isinstance(nb_samples, int)
            or nb_samples < 1
        ):
            raise ValueError("nb_samples must be a positive integer")

        return self.__internal_api__stream_item_chunks__(
            chunk_size=chunk_size,
            num_threads=min(num_threads, constants.DATASET_ITEMS_READ_MAX_THREADS),
            nb_samples=nb_samples,
            filter_string=filter_string,
        )

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
        project_name: Optional[str],
        client: Optional[Any] = None,
    ) -> None:
        self._dataset_name = dataset_name
        self._dataset_id = dataset_id
        self._rest_client = rest_client
        self._version_info = version_info
        self._project_name = project_name
        self.client = client

    @property
    def dataset_name(self) -> str:
        """The name of the dataset this version belongs to."""
        return self._dataset_name

    @property
    def project_name(self) -> Optional[str]:
        """The name of the project this dataset belongs to."""
        return self._project_name

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
            project_name=self._project_name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=self._version_info.version_hash,
        )

    @override
    def __internal_api__stream_item_chunks__(
        self,
        chunk_size: int,
        num_threads: int,
        nb_samples: Optional[int],
        filter_string: Optional[str],
    ) -> Iterator[List[Dict[str, Any]]]:
        return rest_operations.stream_dataset_item_chunks(
            rest_client=self._rest_client,
            dataset_id=self._dataset_id,
            chunk_size=chunk_size,
            num_threads=num_threads,
            nb_samples=nb_samples,
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

    def get_evaluators(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[Any]:
        """
        Get suite-level evaluators for this dataset version.

        DatasetVersion does not support suite-level evaluators, so this always
        returns an empty list.

        Returns:
            Empty list.
        """
        return []

    def get_execution_policy(self) -> execution_policy.ExecutionPolicy:
        """
        Get the execution policy for this dataset version.

        DatasetVersion does not support suite-level execution policy, so this
        returns the default execution policy.

        Returns:
            Default execution policy.
        """
        return execution_policy.DEFAULT_EXECUTION_POLICY.copy()


class Dataset(DatasetExportOperations):
    def __init__(
        self,
        name: str,
        description: Optional[str],
        project_name: Optional[str],
        rest_client: rest_api_client.OpikApi,
        dataset_items_count: Optional[int] = None,
        client: Optional[Any] = None,
        rest_httpx_client: Optional[httpx.Client] = None,
        url_override: Optional[str] = None,
    ) -> None:
        """
        A Dataset object. This object should not be created directly, instead use :meth:`opik.Opik.create_dataset` or :meth:`opik.Opik.get_dataset`.
        """
        self._name = name
        self._description = description
        self._rest_client = rest_client
        self._dataset_items_count = dataset_items_count
        self._project_name = project_name
        self.client = client
        self._rest_httpx_client = rest_httpx_client
        self._url_override = url_override

        self._id_to_hash: Dict[str, str] = {}
        self._hashes: Set[str] = set()
        # True when the local hash cache is consistent with the backend.
        # Directly-constructed Datasets (create_dataset, test-suite helpers,
        # unit tests) start synced — there's nothing on the backend we haven't
        # seen locally. The backend-fetch factories (`from_public`,
        # `rest_operations.get_datasets`) flip this to False so dedup does a
        # one-shot sync on the first `insert()` instead of paying an N+1
        # sync at list time.
        self._hashes_synced: bool = True
        # None until the backend version has actually been determined. Only a
        # conclusive answer is stored, so a probe that failed to reach the
        # backend is retried instead of pinning this dataset to sequential
        # uploads for the rest of the session.
        self._parallel_insert_supported_cache: Optional[bool] = None

    @classmethod
    def from_public(
        cls,
        dataset_fern: rest_dataset_public.DatasetPublic,
        project_name: str,
        rest_client: rest_api_client.OpikApi,
        client: Optional[Any] = None,
    ) -> "Dataset":
        """Build a Dataset from a backend response, resolving the actual project.

        The backend may find the dataset via workspace-wide fallback even when
        the caller's project_name doesn't match the dataset's actual project.
        This method uses project_id from the response to resolve the real
        project name, so downstream calls target the correct project.
        """
        actual_project_name: Optional[str] = None
        if dataset_fern.project_id is not None:
            actual_project_name = rest_client.projects.get_project_by_id(
                dataset_fern.project_id
            ).name

        dataset_ = cls(
            name=dataset_fern.name,
            description=dataset_fern.description,
            project_name=actual_project_name or project_name,
            rest_client=rest_client,
            dataset_items_count=dataset_fern.dataset_items_count,
            client=client,
        )
        # Backend may already hold items we haven't seen; lazy-sync on first
        # insert so content-hash dedup still works without paying a sync now.
        dataset_.__internal_api__hashes_synced__ = False
        # The response already carries the id, so seed the cached_property
        # rather than paying a get-dataset-by-name round trip the first time
        # something (a read, an item delete) needs it.
        if dataset_fern.id is not None:
            dataset_.__dict__["id"] = dataset_fern.id
        return dataset_

    @functools.cached_property
    def id(self) -> str:
        """The id of the dataset"""
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._name, project_name=self._project_name
        ).id

    @property
    def name(self) -> str:
        """The name of the dataset."""
        return self._name

    @property
    def project_name(self) -> Optional[str]:
        """The name of the project this dataset belongs to."""
        return self._project_name

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
            dataset_info = self._rest_client.datasets.get_dataset_by_id(id=self.id)
            self._dataset_items_count = dataset_info.dataset_items_count

        return self._dataset_items_count

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

    def get_evaluators(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[Any]:
        """
        Get suite-level evaluators from the current dataset version.

        Converts EvaluatorItemPublic objects from the BE into LLMJudge instances.

        Args:
            evaluator_model: Optional model name to use for LLMJudge evaluators.

        Returns:
            List of LLMJudge instances extracted from the version.
        """
        from opik.evaluation.suite_evaluators import llm_judge
        from opik.evaluation.suite_evaluators.llm_judge import (
            config as llm_judge_config,
        )

        version_info = self.get_version_info()
        if version_info is None or not version_info.evaluators:
            return []

        evaluators: List[Any] = []
        for evaluator_item in version_info.evaluators:
            try:
                if evaluator_item.type == "llm_judge":
                    cfg = llm_judge_config.LLMJudgeConfig(**evaluator_item.config)
                    evaluator = llm_judge.LLMJudge.from_config(
                        cfg, init_kwargs={"model": evaluator_model}
                    )
                    evaluators.append(evaluator)
                else:
                    LOGGER.warning(
                        "Unsupported evaluator type in version: %s. Only 'llm_judge' is supported.",
                        evaluator_item.type,
                    )
            except Exception:
                LOGGER.error(
                    "Failed to instantiate evaluator from version config: %s",
                    evaluator_item.config,
                    exc_info=True,
                )
                raise

        return evaluators

    def get_execution_policy(
        self,
    ) -> execution_policy.ExecutionPolicy:
        """
        Get suite-level execution policy from the current dataset version.

        Returns:
            ExecutionPolicy dict with runs_per_item and pass_threshold.
        """
        version_info = self.get_version_info()
        if version_info is not None and version_info.execution_policy is not None:
            ep = version_info.execution_policy
            return {
                "runs_per_item": ep.runs_per_item
                if ep.runs_per_item is not None
                else 1,
                "pass_threshold": ep.pass_threshold
                if ep.pass_threshold is not None
                else 1,
            }

        return execution_policy.DEFAULT_EXECUTION_POLICY.copy()

    def get_tags(self) -> List[str]:
        """
        Get the tags for this dataset.

        Returns:
            List of tag strings.
        """
        dataset_fern = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._name, project_name=self._project_name
        )
        return dataset_fern.tags or []

    def _deduplicating(
        self, items: Iterable[dataset_item.DatasetItem], deduplication: bool
    ) -> Iterator[dataset_item.DatasetItem]:
        """Yield items, dropping ones whose content hash has already been seen.

        The hash state spans the whole pass, so a duplicate is caught however far apart
        the two copies are. Hashes always use the standard library, so item identity does
        not depend on which serialiser writes the request body.
        """
        for item in items:
            if deduplication:
                try:
                    item_hash = item.content_hash()
                except (TypeError, ValueError) as exception:
                    # Hashing serialises too, so it reaches a bad value before the writer
                    # does, and raises the writer's error rather than a bare `TypeError`
                    # from `json.dumps`. `ValueError` is how that reports a circular
                    # reference. It does not make the two settings agree everywhere:
                    # hashing sorts keys, so an item with keys of mixed type still fails
                    # here and still uploads with `deduplication=False`.
                    raise streaming_writer.ItemNotSerializableError(
                        f"Dataset item is not JSON-serializable: {exception}"
                    ) from exception
                if item_hash in self._hashes:
                    LOGGER.debug(
                        "Duplicate item found with hash: %s - ignored the event",
                        item_hash,
                    )
                    continue
                self._hashes.add(item_hash)
                # Keyed the way the item is sent, so a later delete by the id the backend
                # returns finds the hash this pass cached.
                self._id_to_hash[streaming_writer.canonical_id(item.id)] = item_hash
            yield item

    def _item_payload(self, item: dataset_item.DatasetItem) -> Dict[str, Any]:
        """Wire form of one dataset item, without building an intermediate model."""
        for field, value in (
            ("id", item.id),
            ("trace_id", item.trace_id),
            ("span_id", item.span_id),
        ):
            streaming_writer.validate_identifier(value, field)
        evaluators = None
        if item.evaluators:
            evaluators = [
                {"name": e.name, "type": e.type, "config": e.config}
                for e in item.evaluators
            ]

        execution_policy_payload = None
        if item.execution_policy:
            execution_policy_payload = {
                "runs_per_item": item.execution_policy.runs_per_item,
                "pass_threshold": item.execution_policy.pass_threshold,
            }

        return streaming_writer.item_payload(
            item_id=item.id,
            trace_id=item.trace_id,
            span_id=item.span_id,
            source=item.source,
            data=item.get_content(),
            description=item.description,
            evaluators=evaluators,
            execution_policy=execution_policy_payload,
        )

    def _upload_transport(self) -> Tuple[httpx.Client, str]:
        """The HTTP client and base URL used to send prepared request bodies.

        A `Dataset` always has a REST client, and the transport underneath it is the very
        `OpikHttpxClient` the owning client holds -- the same object, carrying the same
        auth, workspace headers and compression setting -- so a `Dataset` built from a REST
        client alone resolves a transport like any other, as the read side already does in
        `parallel_items_reader`. The constructor arguments win where they were supplied.
        """
        httpx_client_ = self._rest_httpx_client
        base_url = self._url_override

        if httpx_client_ is None:
            httpx_client_ = self._rest_client._client_wrapper.httpx_client.httpx_client
        if base_url is None:
            base_url = self._rest_client._client_wrapper.get_base_url()

        if httpx_client_ is None or base_url is None:
            raise exceptions.OpikException(
                "The dataset's REST client exposes no HTTP transport to upload through"
            )
        return httpx_client_, base_url

    def _send_prepared_body(self, body: bytes) -> None:
        """Send one already-serialised request body."""
        httpx_client_, base_url = self._upload_transport()

        def send() -> None:
            response = httpx_client.send_prepared_json(
                httpx_client_,
                base_url,
                "v1/private/datasets/items",
                body,
                headers=httpx_client.wrapper_headers(self._rest_client),
            )
            if response.status_code >= 300:
                raise ApiError(
                    status_code=response.status_code,
                    headers=dict(response.headers),
                    body=response.text,
                )

        # `rest_client_configurator` wraps every generated client method in this retry, so
        # a body sent through the raw sender has to carry it too or the bulk path would be
        # the one path that gives up on a transient 5xx. Nested as it is there: retries
        # inside, the rate-limit wait outside.
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            retry_decorator.opik_rest_retry(send)
        )

    def _open_send_pool(self, num_threads: int) -> streaming_writer.BoundedSendPool:
        """Upload sink for one insert. Split out so the worker count is observable."""
        return streaming_writer.BoundedSendPool(self._send_prepared_body, num_threads)

    @property
    def _parallel_insert_supported(self) -> bool:
        """Whether the backend tolerates concurrent batches sharing a batch_group_id.

        Older backends race on them, so parallelism is only safe from
        ``constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT`` onwards. When the
        version cannot be determined we report unsupported rather than risk the
        race.

        The answer is cached because the backend cannot change version
        mid-session and parallel upload is the default: probing per ``insert``
        would add a round trip to every call in a loop. Only a conclusive
        answer is cached — an unreachable backend is re-probed on the next
        insert so parallel upload resumes once it recovers.
        """
        if self._parallel_insert_supported_cache is not None:
            return self._parallel_insert_supported_cache

        try:
            backend_version = self._rest_client.version()["version"]
        except Exception:
            LOGGER.warning(
                "Could not reach the Opik backend to determine its version, "
                "falling back to a sequential dataset upload for this insert.",
                exc_info=True,
            )
            return False

        try:
            supported = (
                semantic_version.SemanticVersion.parse(backend_version)
                >= constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT
            )
        except Exception:
            LOGGER.warning(
                "Could not parse the Opik backend version %s, falling back to a "
                "sequential dataset upload. Parallel upload requires backend %s "
                "or newer.",
                backend_version,
                constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT,
                exc_info=True,
            )
            supported = False
        else:
            if not supported:
                LOGGER.warning(
                    "Opik backend %s does not support parallel dataset upload, "
                    "falling back to a sequential upload. Upgrade to backend %s or "
                    "newer to use num_threads.",
                    backend_version,
                    constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT,
                )

        self._parallel_insert_supported_cache = supported
        return supported

    def __internal_api__insert_items_as_dataclasses__(
        self,
        items: Iterable[dataset_item.DatasetItem],
        num_threads: int = 1,
        deduplication: bool = True,
    ) -> None:
        # Validated here rather than in each public entry point: every insert
        # path funnels through this method. A truthy string or None would
        # otherwise silently pick the wrong duplicate-checking behaviour, and
        # a non-integer worker count would fail on the comparison below with a
        # TypeError instead of naming the offending argument.
        if not isinstance(deduplication, bool):
            raise ValueError("deduplication must be a bool")
        if isinstance(num_threads, bool) or not isinstance(num_threads, int):
            raise ValueError("num_threads must be a positive integer")
        if num_threads < 1:
            raise ValueError("num_threads must be a positive integer")

        # Gated here rather than in `insert` so every caller of this funnel is
        # covered: older backends race on concurrent batches that share a
        # batch_group_id, and a direct caller asking for workers must not be
        # able to skip that check.
        if num_threads > 1 and not self._parallel_insert_supported:
            num_threads = 1

        if deduplication:
            # Lazy-sync against the backend the first time we insert into a dataset that
            # was fetched from it, so content-hash dedup still works without paying an
            # N+1 sync at list time.
            if not self._hashes_synced:
                self.__internal_api__sync_hashes__()
        else:
            # Nothing will be hashed, so the local cache no longer describes the backend;
            # force a re-sync before the next deduplicated insert.
            self._hashes_synced = False

        opik_config = config.OpikConfig()
        batch_group_id = id_helpers.generate_id()

        try:
            upload_client, _ = self._upload_transport()
            # The enable flag gates the level: a client built with compression off must
            # not be handed gzipped bodies, whatever level is configured. A transport that
            # carries no setting of its own -- a REST client built directly sends through
            # a plain httpx client -- takes the configured one, the same config the level
            # and the serialiser are read from.
            compressing = httpx_client.compresses_json_requests(
                upload_client, default=opik_config.enable_json_request_compression
            )

            pool = self._open_send_pool(num_threads)
            writer = streaming_writer.StreamingBatchWriter(
                envelope={
                    "dataset_name": self._name,
                    "project_name": self._project_name,
                    "batch_group_id": batch_group_id,
                },
                flush_callback=pool.submit,
                max_payload_bytes=int(config.MAX_BATCH_SIZE_MB * 1024 * 1024),
                max_items=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
                flush_interval_seconds=constants.DATASET_ITEMS_FLUSH_INTERVAL_SECONDS,
                gzip_level=(
                    opik_config.dataset_upload_compression_level
                    if compressing
                    else None
                ),
                use_orjson=opik_config.enable_orjson_serialization,
            )

            try:
                for item in self._deduplicating(items, deduplication):
                    writer.add(self._item_payload(item))
                writer.flush()
            except BaseException:
                # Still close and join the pool, but let the producer's exception stand:
                # a body that failed earlier would otherwise replace the error that
                # explains why the upload stopped here.
                try:
                    pool.close()
                except Exception:
                    LOGGER.debug(
                        "A dataset upload batch also failed while closing the pool",
                        exc_info=True,
                    )
                raise
            else:
                pool.close()
        finally:
            # In a `finally`, and around both paths, because a partial insert still
            # changed the dataset: an upload that fails after earlier bodies landed, or a
            # source that raises part-way, leaves items on the backend that a cached count
            # taken before the insert does not include.
            self._dataset_items_count = None

    def insert(
        self,
        items: Iterable[Union[Dict[str, Any], dataset_item.DatasetItem]],
        num_threads: int = 4,
        deduplication: bool = True,
    ) -> None:
        """
        Insert new items into the dataset. A new dataset version will be created.

        Args:
            items: Dicts (or ``DatasetItem`` objects) to add to the dataset. Any
                iterable is accepted, including a generator, and it is consumed lazily, so
                no item is retained once its request has been sent and the request bodies
                in flight are capped. That is the bounded part; deduplication is not, and
                keeps a content digest and an id per item for the life of the ``Dataset``
                however the items arrived -- pass ``deduplication=False`` for an upload
                that retains nothing at all. A list keeps working as before, and its
                items are checked for shape before the first request goes out; from a
                generator not even that is possible. Either way a value that cannot be
                serialised is found when the item carrying it is reached, and the items
                sent before it stay persisted -- a single-pass upload cannot know the
                last item is invalid before sending the first batch.
            deduplication: Whether to skip items whose content already exists
                in the dataset. Pass ``False`` to insert every item as-is
                without any duplicate checking, which is significantly faster
                on large datasets. The next insert that does deduplicate has to
                re-read the dataset's items to account for what was skipped.
            num_threads: Number of worker threads used to upload the item
                batches. Must be a positive integer, defaults to ``4``; pass
                ``1`` to upload sequentially. All batches land in a single
                dataset version. If a batch fails the call raises, and the
                batches that already succeeded stay persisted. Older Opik
                backends do not support parallel upload and fall back to a
                sequential one.

        Raises:
            ValueError: If ``num_threads`` is not a positive integer, if
                ``deduplication`` is not a bool, if an item in a list is neither a dict
                nor a ``DatasetItem``, or if an item's ``id``, ``trace_id`` or
                ``span_id`` is not a UUID.
        """
        # Repeated from the funnel so a bad argument raises before the shape pre-pass
        # below walks the whole input.
        if isinstance(num_threads, bool) or not isinstance(num_threads, int):
            raise ValueError("num_threads must be a positive integer")
        if num_threads < 1:
            raise ValueError("num_threads must be a positive integer")
        if not isinstance(deduplication, bool):
            raise ValueError("deduplication must be a bool")

        if isinstance(items, collections.abc.Sequence):
            # Cheap enough to run over the whole input before anything is sent -- one
            # isinstance per item, nothing retained -- and it buys atomicity: an item of
            # the wrong type raises before the first request rather than part-way through
            # the upload with earlier items already persisted. Shape only; a value that
            # cannot be serialised is still found when it is reached.
            for index, item in enumerate(items):
                if not isinstance(item, (dict, dataset_item.DatasetItem)):
                    raise ValueError(
                        f"Dataset item at index {index} must be a dict or a DatasetItem, "
                        f"got {type(item).__name__}"
                    )
                # Named with its position while we still have one; the same check runs
                # per item further down, where a generator gives no index to report.
                for field in ("id", "trace_id", "span_id"):
                    supplied = (
                        item.get(field)
                        if isinstance(item, dict)
                        else getattr(item, field, None)
                    )
                    streaming_writer.validate_identifier(supplied, field, index)

        # A generator rather than a list: converting lazily is what lets a generator
        # argument stay un-materialised all the way to the wire.
        dataset_items = (
            (dataset_item.DatasetItem(**item) if isinstance(item, dict) else item)
            for item in items
        )
        self.__internal_api__insert_items_as_dataclasses__(
            dataset_items,
            num_threads=num_threads,
            deduplication=deduplication,
        )

    @property
    def __internal_api__hashes_synced__(self) -> bool:
        """Whether the local hash cache is in sync with the backend.

        `__init__` defaults this to True (a freshly constructed Dataset
        has no backend state to sync). Factory paths that construct a
        Dataset from an existing backend state (`from_public`,
        `rest_operations.get_datasets`) flip it to False so the first
        :meth:`insert` triggers a one-shot sync instead of paying an
        N+1 sync at list time.
        """
        return self._hashes_synced

    @__internal_api__hashes_synced__.setter
    def __internal_api__hashes_synced__(self, value: bool) -> None:
        self._hashes_synced = value

    def __internal_api__sync_hashes__(self) -> None:
        """Updates all the hashes in the dataset"""
        LOGGER.debug("Start hash sync in dataset")

        self._id_to_hash = {}
        self._hashes = set()

        for item in self.__internal_api__stream_items_as_dataclasses__():
            item_hash = item.content_hash()
            self._id_to_hash[streaming_writer.canonical_id(item.id)] = item_hash
            self._hashes.add(item_hash)

        self._hashes_synced = True
        LOGGER.debug("Finish hash sync in dataset")

    def update(
        self, items: Iterable[Dict[str, Any]], deduplication: bool = True
    ) -> None:
        """
        Update existing items in the dataset.

        Args:
            items: Dicts to update in the dataset. You need to provide the full item
                object as it will override what has been supplied previously. Any
                iterable is accepted, including a generator.
            deduplication: Whether to skip items whose content already exists in
                the dataset. See :meth:`insert` for details.

        Raises:
            DatasetItemUpdateOperationRequiresItemId: If an item is missing an id. The
                item's position in the input is included in the message. A list is
                scanned before anything is sent; from a generator the missing id is
                found when its item is reached, and the items before it stay persisted.
        """

        def require_id(index: int, item: Dict[str, Any], consequence: str) -> None:
            if "id" not in item:
                raise exceptions.DatasetItemUpdateOperationRequiresItemId(
                    f"Missing id for dataset item at index {index}: {item}. {consequence}"
                )

        def checked(
            source: Iterable[Dict[str, Any]],
        ) -> Iterator[Dict[str, Any]]:
            for index, item in enumerate(source):
                require_id(
                    index, item, "Items before it may already have been persisted."
                )
                yield item

        if isinstance(items, collections.abc.Sequence):
            # Scannable up front, the way `insert` scans a list for shape, so the
            # atomicity `update` had before it streamed is kept where it is still
            # possible. A generator cannot be scanned without consuming it.
            for index, item in enumerate(items):
                require_id(index, item, "Nothing has been sent.")
            self.insert(items, deduplication=deduplication)
        else:
            self.insert(checked(items), deduplication=deduplication)

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
            items_ids: List of item ids to delete. Ids are normalised the way
                :meth:`insert` normalises them, so an item inserted with a ``uuid.UUID``
                object can be deleted by that object or by its string form.

        Raises:
            ValueError: If an id is ``None`` or empty. The item's position in the input
                is included in the message.
        """
        # Through the same canonicalisation the upload used, so an id given here in a
        # different form than it was inserted in still matches the cached hash.
        canonical_ids = []
        for index, id_ in enumerate(items_ids):
            canonical = streaming_writer.canonical_id(id_)
            # Neither identifies an item, and both reach the backend as a request to
            # delete nothing in particular rather than as an error.
            if not canonical:
                raise ValueError(
                    f"Dataset item id at index {index} must be a non-empty value, "
                    f"got {id_!r}"
                )
            canonical_ids.append(canonical)
        batches = sequence_splitter.split_into_batches(
            canonical_ids, max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE
        )

        batch_group_id = id_helpers.generate_id()

        try:
            for batch in batches:
                LOGGER.debug(
                    "Deleting dataset items batch of size %d, first ids: %s",
                    len(batch),
                    batch[:5],
                )
                self._delete_batch_with_retry(batch, batch_group_id=batch_group_id)

                for item_id in batch:
                    if item_id in self._id_to_hash:
                        hash = self._id_to_hash[item_id]
                        self._hashes.discard(hash)
                        del self._id_to_hash[item_id]
        finally:
            # In a `finally` for the same reason the insert path is: a delete that fails
            # part-way has already removed the batches before it, so a count cached from
            # before the call no longer describes the dataset -- and would be reported
            # indefinitely, since the cache is only refilled once cleared.
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
            project_name=self._project_name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=None,
        )

    @override
    def __internal_api__stream_item_chunks__(
        self,
        chunk_size: int,
        num_threads: int,
        nb_samples: Optional[int],
        filter_string: Optional[str],
    ) -> Iterator[List[Dict[str, Any]]]:
        # A generator, so `self.id` -- which resolves the dataset by name over
        # REST when it hasn't been seeded -- is not touched until the caller
        # actually starts iterating. Keeps `stream_items()` free of I/O.
        yield from rest_operations.stream_dataset_item_chunks(
            rest_client=self._rest_client,
            dataset_id=self.id,
            chunk_size=chunk_size,
            num_threads=num_threads,
            nb_samples=nb_samples,
            filter_string=filter_string,
            dataset_version=self._resolve_read_version(),
        )

    def _resolve_read_version(self) -> Optional[str]:
        """The version hash every page of one read is pinned to, if there is one.

        Pages are addressed by offset, and the backend sorts newest id first, so
        an item inserted mid-read lands at offset 0 and shifts every page that
        has not been fetched yet -- returning one item twice and skipping
        another. Reading a single version instead makes the whole read a
        snapshot, which is what the cursor-based stream got for free from its
        ``id < last_retrieved_id`` seek.

        Returns None when the backend has no version to pin to (versioning
        disabled, or a dataset with no versions yet); the read then falls back
        to the live state and stays vulnerable to that shift, which is called
        out on :meth:`stream_items`.
        """
        version_info = self.get_version_info()
        version_hash = version_info.version_hash if version_info else None

        if version_hash is None:
            LOGGER.debug(
                "No dataset version to pin the read of dataset %s to; reading "
                "the live state, which may return an item twice or skip one if "
                "items are inserted or deleted while the read is in progress.",
                self._name,
            )

        return version_hash

    def insert_from_json(
        self,
        json_array: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
        deduplication: bool = True,
    ) -> None:
        """
        Args:
            json_array: json string of format: "[{...}, {...}, {...}]" where every dictionary
                is to be transformed into dataset item
            keys_mapping: dictionary that maps json keys to item fields names
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your json dicts contain keys that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
            deduplication: Whether to skip items whose content already exists in
                the dataset. See :meth:`insert` for details.
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_json(
            json_array, keys_mapping=keys_mapping, ignore_keys=ignore_keys
        )

        self.insert(new_items, deduplication=deduplication)

    def read_jsonl_from_file(
        self,
        file_path: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
        deduplication: bool = True,
        validate_before_upload: bool = True,
    ) -> None:
        """
        Read JSONL from a file and insert it into the dataset.

        The file is parsed one line at a time and uploaded as it is read, so a file
        larger than memory can be inserted whichever way ``validate_before_upload`` is
        set: neither the file nor the items it holds are retained. Deduplication is the
        exception and is unchanged -- with ``deduplication=True`` a digest and an id per
        item are kept for the life of the ``Dataset``, around 0.3 KB each.

        The file is read from the start twice when ``validate_before_upload`` is on, so
        it has to be re-readable. A path that cannot be re-read -- a pipe or a character
        device -- is uploaded in a single pass instead, and a warning says so, rather
        than validating the stream and then finding nothing left to upload.

        Args:
            file_path: Path to the JSONL file
            keys_mapping: dictionary that maps json keys to item fields names
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your json dicts contain keys that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
            deduplication: Whether to skip items whose content already exists in
                the dataset. See :meth:`insert` for details.
            validate_before_upload: When the file is checked, not whether. Every item
                is validated either way. ``True`` (the default) reads the file once
                first, so a bad line raises before any request -- the check
                :meth:`insert` runs on a list and cannot run on a generator -- at the
                cost of a second parse, about 17% of wall time on a 228 MiB file and no
                memory. ``False`` uploads in a single pass and validates each item as it
                is sent, so a bad line raises when it is reached, with the items before
                it persisted and no rollback.

        Raises:
            ValueError: If an item's ``id``, ``trace_id`` or ``span_id`` is not a UUID.
                With ``validate_before_upload`` it names the item's position among the
                items read -- blank lines are skipped, so that is not a line number --
                and nothing has been sent; a malformed line, or a value pydantic
                rejects, is raised there too. A value that cannot be serialised is found when its
                item is reached either way, as it is for a list.
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        def items() -> Iterator[dataset_item.DatasetItem]:
            return converters.stream_from_jsonl_file(
                file_path, keys_mapping, ignore_keys
            )

        if validate_before_upload and not os.path.isfile(file_path):
            # Re-opening a pipe lands at EOF, so the check would pass over the whole
            # stream and the upload would then send nothing at all and return happily.
            LOGGER.warning(
                "%s cannot be read twice, so its items are validated as they are sent "
                "rather than before the first request.",
                file_path,
            )
            validate_before_upload = False

        if validate_before_upload:
            # A file can be read twice, so it gets the check a list gets and a generator
            # cannot: one pass that parses every line and builds every item, keeping
            # none of them. Validating by materialising the items instead would hold
            # ~1.3 KB each until the upload ends; this holds one line.
            for index, item in enumerate(items()):
                for field in ("id", "trace_id", "span_id"):
                    streaming_writer.validate_identifier(
                        getattr(item, field, None), field, index
                    )

        self.insert(items(), deduplication=deduplication)

    def insert_from_pandas(
        self,
        dataframe: "pd.DataFrame",
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
        deduplication: bool = True,
    ) -> None:
        """
        Requires: `pandas` library to be installed.

        Args:
            dataframe: pandas dataframe
            keys_mapping: Dictionary that maps dataframe column names to dataset item field names.
                Example: {'Expected output': 'expected_output'}
            ignore_keys: if your dataframe contains columns that are not needed for DatasetItem
                construction - pass them as ignore_keys argument
            deduplication: Whether to skip items whose content already exists in
                the dataset. See :meth:`insert` for details.
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_pandas(dataframe, keys_mapping, ignore_keys)

        self.insert(new_items, deduplication=deduplication)

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
            project_name=self._project_name,
            client=self.client,
        )
