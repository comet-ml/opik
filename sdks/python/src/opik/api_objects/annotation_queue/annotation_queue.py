import functools
import logging
from typing import (
    Optional,
    Any,
    List,
    Callable,
    Literal,
    Union,
    TypeVar,
)

from opik.rest_api import client as rest_api_client
from opik.rest_api.types import trace_public, trace_thread
from opik.message_processing.batching import sequence_splitter
from opik.api_objects.trace import trace_client
from opik.api_objects.rest_helpers import ensure_rest_api_call_respecting_rate_limit
import opik.exceptions as exceptions

LOGGER = logging.getLogger(__name__)

ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000

TraceType = Union[trace_client.Trace, trace_public.TracePublic]

F = TypeVar("F", bound=Callable[..., Any])


def _require_scope(
    expected_scope: Literal["trace", "thread"],
    item_type: str,
) -> Callable[[F], F]:
    """Decorator that validates the queue scope matches the expected scope."""

    def decorator(func: F) -> F:
        @functools.wraps(func)
        def wrapper(self: "AnnotationQueue", *args: Any, **kwargs: Any) -> Any:
            if self._scope != expected_scope:
                raise exceptions.AnnotationQueueScopeMismatch(
                    item_type=item_type,
                    queue_scope=self._scope,
                )
            return func(self, *args, **kwargs)

        return wrapper  # type: ignore[return-value]

    return decorator


class AnnotationQueue:
    """
    An AnnotationQueue object representing a queue for human annotation workflows.

    This object should not be created directly, instead use
    :meth:`opik.Opik.create_annotation_queue` or :meth:`opik.Opik.get_annotation_queue`.
    """

    def __init__(
        self,
        id: str,
        name: str,
        project_id: str,
        scope: Literal["trace", "thread"],
        rest_client: rest_api_client.OpikApi,
        description: Optional[str] = None,
        instructions: Optional[str] = None,
        comments_enabled: Optional[bool] = None,
        feedback_definition_names: Optional[List[str]] = None,
        items_count: Optional[int] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._description = description
        self._instructions = instructions
        self._project_id = project_id
        self._scope = scope
        self._comments_enabled = comments_enabled
        self._feedback_definition_names = feedback_definition_names
        self._items_count = items_count
        self._rest_client = rest_client

    @property
    def id(self) -> str:
        """The id of the annotation queue."""
        return self._id

    @property
    def name(self) -> str:
        """The name of the annotation queue."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the annotation queue."""
        return self._description

    @property
    def instructions(self) -> Optional[str]:
        """The instructions for reviewers."""
        return self._instructions

    @property
    def project_id(self) -> str:
        """The project ID associated with this annotation queue."""
        return self._project_id

    @property
    def scope(self) -> Literal["trace", "thread"]:
        """The scope of the annotation queue ('trace' or 'thread')."""
        return self._scope

    @property
    def comments_enabled(self) -> Optional[bool]:
        """Whether comments are enabled for this queue."""
        return self._comments_enabled

    @property
    def feedback_definition_names(self) -> Optional[List[str]]:
        """The feedback definition names associated with this queue."""
        return self._feedback_definition_names

    @property
    def items_count(self) -> Optional[int]:
        """
        The total number of items in the queue.

        If the count is not cached locally, it will be fetched from the backend.
        """
        if self._items_count is None:
            queue_info = self._rest_client.annotation_queues.get_annotation_queue_by_id(
                self._id
            )
            self._items_count = queue_info.items_count
        return self._items_count

    def update(
        self,
        name: Optional[str] = None,
        description: Optional[str] = None,
        instructions: Optional[str] = None,
        comments_enabled: Optional[bool] = None,
        feedback_definition_names: Optional[List[str]] = None,
    ) -> None:
        """
        Update the annotation queue properties.

        Args:
            name: New name for the queue.
            description: New description for the queue.
            instructions: New instructions for reviewers.
            comments_enabled: Whether to enable comments.
            feedback_definition_names: List of feedback definition names.
        """
        self._rest_client.annotation_queues.update_annotation_queue(
            id=self._id,
            name=name,
            description=description,
            instructions=instructions,
            comments_enabled=comments_enabled,
            feedback_definition_names=feedback_definition_names,
        )

        if name is not None:
            self._name = name
        if description is not None:
            self._description = description
        if instructions is not None:
            self._instructions = instructions
        if comments_enabled is not None:
            self._comments_enabled = comments_enabled
        if feedback_definition_names is not None:
            self._feedback_definition_names = feedback_definition_names

    def delete(self) -> None:
        """
        Delete this annotation queue.
        """
        self._rest_client.annotation_queues.delete_annotation_queue_batch(
            ids=[self._id]
        )

    def _add_items_batch_with_retry(self, ids: List[str]) -> None:
        """Add a batch of items with automatic retry on rate limit errors."""
        ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.annotation_queues.add_items_to_annotation_queue(
                id=self._id, ids=ids
            )
        )
        LOGGER.debug("Successfully added %d items to annotation queue", len(ids))

    def _remove_items_batch_with_retry(self, ids: List[str]) -> None:
        """Remove a batch of items with automatic retry on rate limit errors."""
        ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.annotation_queues.remove_items_from_annotation_queue(
                id=self._id, ids=ids
            )
        )
        LOGGER.debug("Successfully removed %d items from annotation queue", len(ids))

    def _extract_trace_ids(
        self,
        traces: List[TraceType],
    ) -> List[str]:
        """Extract IDs from trace objects."""
        ids: List[str] = []
        for trace in traces:
            if trace.id is None:
                raise exceptions.OpikException("Trace object has no id")
            ids.append(trace.id)

        return ids

    def _extract_thread_ids(
        self,
        threads: List[trace_thread.TraceThread],
    ) -> List[str]:
        """Extract thread_model_id from TraceThread objects."""
        ids: List[str] = []
        for thread in threads:
            if thread.thread_model_id is None:
                raise exceptions.OpikException(
                    "TraceThread object has no thread_model_id"
                )
            ids.append(thread.thread_model_id)

        return ids

    @_require_scope(expected_scope="trace", item_type="traces")
    def add_traces(
        self,
        traces: List[TraceType],
    ) -> None:
        """
        Add trace objects to the annotation queue.

        Args:
            traces: A list of traces to add. For a single trace, wrap it in a list: [trace].
                Accepts Trace objects (from opik_client.trace()) or TracePublic objects
                (from search_traces()).

        Raises:
            AnnotationQueueScopeMismatch: If the queue scope is not 'trace'.
            OpikException: If any trace object has no id.
        """
        ids = self._extract_trace_ids(traces)
        if not ids:
            return

        batches = sequence_splitter.split_into_batches(
            ids, max_length=ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Adding %d traces to annotation queue", len(batch))
            self._add_items_batch_with_retry(batch)

        self._items_count = None

    @_require_scope(expected_scope="thread", item_type="threads")
    def add_threads(
        self,
        threads: List[trace_thread.TraceThread],
    ) -> None:
        """
        Add thread objects to the annotation queue.

        Args:
            threads: A list of TraceThread objects to add (from search_threads()).
                For a single thread, wrap it in a list: [thread].

        Raises:
            AnnotationQueueScopeMismatch: If the queue scope is not 'thread'.
            OpikException: If any thread object has no thread_model_id.
        """
        ids = self._extract_thread_ids(threads)
        if not ids:
            return

        batches = sequence_splitter.split_into_batches(
            ids, max_length=ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Adding %d threads to annotation queue", len(batch))
            self._add_items_batch_with_retry(batch)

        self._items_count = None

    @_require_scope(expected_scope="trace", item_type="traces")
    def remove_traces(
        self,
        traces: List[TraceType],
    ) -> None:
        """
        Remove trace objects from the annotation queue.

        Args:
            traces: A list of traces to remove. For a single trace, wrap it in a list: [trace].
                Accepts Trace objects (from opik_client.trace()) or TracePublic objects
                (from search_traces()).

        Raises:
            AnnotationQueueScopeMismatch: If the queue scope is not 'trace'.
            OpikException: If any trace object has no id.
        """
        ids = self._extract_trace_ids(traces)
        if not ids:
            return

        batches = sequence_splitter.split_into_batches(
            ids, max_length=ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Removing %d traces from annotation queue", len(batch))
            self._remove_items_batch_with_retry(batch)

        self._items_count = None

    @_require_scope(expected_scope="thread", item_type="threads")
    def remove_threads(
        self,
        threads: List[trace_thread.TraceThread],
    ) -> None:
        """
        Remove thread objects from the annotation queue.

        Args:
            threads: A list of TraceThread objects to remove (from search_threads()).
                For a single thread, wrap it in a list: [thread].

        Raises:
            AnnotationQueueScopeMismatch: If the queue scope is not 'thread'.
            OpikException: If any thread object has no thread_model_id.
        """
        ids = self._extract_thread_ids(threads)
        if not ids:
            return

        batches = sequence_splitter.split_into_batches(
            ids, max_length=ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Removing %d threads from annotation queue", len(batch))
            self._remove_items_batch_with_retry(batch)

        self._items_count = None
