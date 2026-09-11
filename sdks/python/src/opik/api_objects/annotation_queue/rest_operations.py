from __future__ import annotations

import json
from typing import Callable, List, Optional, Type, TypeVar

from opik.rest_api import OpikApi
from opik.rest_api.types import AnnotationQueuePublic
import opik.exceptions as exceptions
from . import annotation_queue
from ...rest_api.core.api_error import ApiError

QueueT = TypeVar(
    "QueueT",
    annotation_queue.TracesAnnotationQueue,
    annotation_queue.ThreadsAnnotationQueue,
)


def _create_queue_instance(
    queue_data: AnnotationQueuePublic,
    rest_client: OpikApi,
    queue_class: Type[QueueT],
) -> QueueT:
    """Helper to create an annotation queue instance from API response data."""
    return queue_class(
        id=queue_data.id or "",
        name=queue_data.name,
        project_id=queue_data.project_id,
        rest_client=rest_client,
        description=queue_data.description,
        instructions=queue_data.instructions,
        comments_enabled=queue_data.comments_enabled,
        feedback_definition_names=list(queue_data.feedback_definition_names)
        if queue_data.feedback_definition_names
        else None,
        items_count=queue_data.items_count,
    )


def _get_annotation_queues_by_scope(
    rest_client: OpikApi,
    queue_class: Type[QueueT],
    scope_filter: Callable[[Optional[str]], bool],
    project_id: Optional[str] = None,
    max_results: int = 1000,
) -> List[QueueT]:
    """Helper to fetch annotation queues filtered by scope."""
    page_size = 100
    queues: List[QueueT] = []

    filters: Optional[str] = None
    if project_id is not None:
        filters = json.dumps(
            [
                {
                    "field": "project_id",
                    "type": "string",
                    "operator": "=",
                    "value": project_id,
                }
            ]
        )

    page = 1
    while len(queues) < max_results:
        page_queues = rest_client.annotation_queues.find_annotation_queues(
            page=page,
            size=page_size,
            filters=filters,
        )

        if page_queues.content is None or len(page_queues.content) == 0:
            break

        for queue_data in page_queues.content:
            if len(queues) >= max_results:
                break
            if scope_filter(queue_data.scope):
                queues.append(
                    _create_queue_instance(queue_data, rest_client, queue_class)
                )

        page += 1

    return queues


def _get_annotation_queue_by_id_with_scope(
    rest_client: OpikApi,
    queue_id: str,
    queue_class: Type[QueueT],
    scope_check: Callable[[Optional[str]], bool],
    scope_name: str,
) -> QueueT:
    """Helper to fetch an annotation queue by ID with scope validation."""
    try:
        queue_data = rest_client.annotation_queues.get_annotation_queue_by_id(queue_id)
    except ApiError as e:
        if e.status_code == 404:
            raise exceptions.OpikException(
                f"Annotation queue with id '{queue_id}' not found."
            ) from e
        raise

    if not scope_check(queue_data.scope):
        actual_scope = queue_data.scope or "trace"
        raise exceptions.OpikException(
            f"Annotation queue with id '{queue_id}' is not a {scope_name} queue (scope: {actual_scope})."
        )

    return _create_queue_instance(queue_data, rest_client, queue_class)


def get_traces_annotation_queues(
    rest_client: OpikApi,
    project_id: Optional[str] = None,
    max_results: int = 1000,
) -> List[annotation_queue.TracesAnnotationQueue]:
    """
    Fetch trace annotation queues with optional project filtering.

    Args:
        rest_client: The REST API client.
        project_id: Optional project ID to filter queues.
        max_results: Maximum number of queues to return.

    Returns:
        A list of TracesAnnotationQueue objects.
    """
    return _get_annotation_queues_by_scope(
        rest_client=rest_client,
        queue_class=annotation_queue.TracesAnnotationQueue,
        scope_filter=lambda s: s == "trace",
        project_id=project_id,
        max_results=max_results,
    )


def get_threads_annotation_queues(
    rest_client: OpikApi,
    project_id: Optional[str] = None,
    max_results: int = 1000,
) -> List[annotation_queue.ThreadsAnnotationQueue]:
    """
    Fetch thread annotation queues with optional project filtering.

    Args:
        rest_client: The REST API client.
        project_id: Optional project ID to filter queues.
        max_results: Maximum number of queues to return.

    Returns:
        A list of ThreadsAnnotationQueue objects.
    """
    return _get_annotation_queues_by_scope(
        rest_client=rest_client,
        queue_class=annotation_queue.ThreadsAnnotationQueue,
        scope_filter=lambda s: s == "thread",
        project_id=project_id,
        max_results=max_results,
    )


def get_traces_annotation_queue_by_id(
    rest_client: OpikApi,
    queue_id: str,
) -> annotation_queue.TracesAnnotationQueue:
    """
    Fetch a trace annotation queue by its ID.

    Args:
        rest_client: The REST API client.
        queue_id: The ID of the annotation queue.

    Returns:
        A TracesAnnotationQueue object.

    Raises:
        OpikException: If the queue is not found or is not a trace queue.
    """
    return _get_annotation_queue_by_id_with_scope(
        rest_client=rest_client,
        queue_id=queue_id,
        queue_class=annotation_queue.TracesAnnotationQueue,
        scope_check=lambda s: s == "trace",
        scope_name="traces",
    )


def get_threads_annotation_queue_by_id(
    rest_client: OpikApi,
    queue_id: str,
) -> annotation_queue.ThreadsAnnotationQueue:
    """
    Fetch a thread annotation queue by its ID.

    Args:
        rest_client: The REST API client.
        queue_id: The ID of the annotation queue.

    Returns:
        A ThreadsAnnotationQueue object.

    Raises:
        OpikException: If the queue is not found or is not a thread queue.
    """
    return _get_annotation_queue_by_id_with_scope(
        rest_client=rest_client,
        queue_id=queue_id,
        queue_class=annotation_queue.ThreadsAnnotationQueue,
        scope_check=lambda s: s == "thread",
        scope_name="threads",
    )
