from __future__ import annotations

import json
from typing import List, Optional, Literal

from opik.rest_api import OpikApi
import opik.exceptions as exceptions
from . import annotation_queue
from ...rest_api.core.api_error import ApiError


def get_annotation_queues(
    rest_client: OpikApi,
    project_id: Optional[str] = None,
    max_results: int = 100,
) -> List[annotation_queue.AnnotationQueue]:
    """
    Fetch annotation queues with optional project filtering.

    Args:
        rest_client: The REST API client.
        project_id: Optional project ID to filter queues.
        max_results: Maximum number of queues to return.

    Returns:
        A list of AnnotationQueue objects.
    """
    page_size = 100
    queues: List[annotation_queue.AnnotationQueue] = []

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

        for queue_data in page_queues.content[: (max_results - len(queues))]:
            scope: Literal["trace", "thread"] = "trace"
            if queue_data.scope is not None:
                scope = "thread" if queue_data.scope == "thread" else "trace"

            queue = annotation_queue.AnnotationQueue(
                id=queue_data.id,  # type: ignore
                name=queue_data.name,
                project_id=queue_data.project_id,
                scope=scope,
                rest_client=rest_client,
                description=queue_data.description,
                instructions=queue_data.instructions,
                comments_enabled=queue_data.comments_enabled,
                feedback_definition_names=list(queue_data.feedback_definition_names)
                if queue_data.feedback_definition_names
                else None,
                items_count=queue_data.items_count,
            )

            queues.append(queue)

        page += 1

    return queues


def get_annotation_queue_by_id(
    rest_client: OpikApi,
    queue_id: str,
) -> annotation_queue.AnnotationQueue:
    """
    Fetch an annotation queue by its ID.

    Args:
        rest_client: The REST API client.
        queue_id: The ID of the annotation queue.

    Returns:
        An AnnotationQueue object.

    Raises:
        AnnotationQueueNotFound: If the queue is not found.
    """
    try:
        queue_data = rest_client.annotation_queues.get_annotation_queue_by_id(queue_id)
    except ApiError as e:
        if e.status_code == 404:
            raise exceptions.OpikException(
                f"Annotation queue with id '{queue_id}' not found."
            ) from e
        raise

    scope: Literal["trace", "thread"] = "trace"
    if queue_data.scope is not None:
        scope = "thread" if queue_data.scope == "thread" else "trace"

    return annotation_queue.AnnotationQueue(
        id=queue_data.id,  # type: ignore
        name=queue_data.name,
        project_id=queue_data.project_id,
        scope=scope,
        rest_client=rest_client,
        description=queue_data.description,
        instructions=queue_data.instructions,
        comments_enabled=queue_data.comments_enabled,
        feedback_definition_names=list(queue_data.feedback_definition_names)
        if queue_data.feedback_definition_names
        else None,
        items_count=queue_data.items_count,
    )
