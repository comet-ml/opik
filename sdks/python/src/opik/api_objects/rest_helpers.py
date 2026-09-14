import asyncio
import logging
import time
from typing import Any, Awaitable, Callable, Optional

from ..rest_api import client as rest_api_client
from ..rest_api.core.api_error import ApiError
from ..rate_limit import rate_limit

LOGGER = logging.getLogger(__name__)


async def _sleep_async(seconds: float) -> None:
    # Same indirection as `_sleep`, so a test can stub the async wait too.
    await asyncio.sleep(seconds)


def _sleep(seconds: float) -> None:
    # Indirection over time.sleep so tests can stub the rate-limit retry delay
    # without patching the global time.sleep — patching the global one turns the
    # pacing sleep of background daemon threads (e.g. QueueConsumer) into a busy
    # loop, which can starve the interpreter and hang the suite.
    time.sleep(seconds)


def ensure_rest_api_call_respecting_rate_limit(
    rest_callable: Callable[[], Any],
    operation_name: Optional[str] = None,
) -> Any:
    """
    Execute a REST API call with automatic retry on rate limit (429) errors.

    This function handles HTTP 429 rate limit errors by waiting for the duration
    specified in the response headers and retrying the request. Regular retries
    for other errors are handled by the underlying rest client.

    Args:
        rest_callable: A callable that performs the REST API call.
        operation_name: Optional label included in rate-limit log messages so users
            can identify which SDK operation is being throttled.

    Returns:
        The result of the successful REST API call.

    Raises:
        ApiError: If the error is not a 429 rate limit error.
    """
    label = f" for '{operation_name}'" if operation_name else ""
    while True:
        try:
            return rest_callable()
        except ApiError as exception:
            retry_after = _rate_limit_delay(exception, label)
            if retry_after is None:
                raise
            _sleep(retry_after)


async def ensure_rest_api_call_respecting_rate_limit_async(
    rest_callable: Callable[[], Awaitable[Any]],
    operation_name: Optional[str] = None,
) -> Any:
    """Async twin of `ensure_rest_api_call_respecting_rate_limit`.

    Sleeps on the event loop rather than on the thread, so one throttled upload does not
    stall the others sharing that loop.
    """
    label = f" for '{operation_name}'" if operation_name else ""
    while True:
        try:
            return await rest_callable()
        except ApiError as exception:
            retry_after = _rate_limit_delay(exception, label)
            if retry_after is None:
                raise
            await _sleep_async(retry_after)


def _rate_limit_delay(exception: ApiError, label: str) -> Optional[float]:
    """How long to wait before retrying, or None when this is not a rate limit."""
    if exception.status_code != 429:
        return None

    if exception.headers is not None:
        rate_limiter = rate_limit.parse_rate_limit(exception.headers)
        if rate_limiter is not None:
            retry_after: float = rate_limiter.retry_after()
            LOGGER.warning(
                "Rate limited (HTTP 429)%s, continuing in %s seconds",
                label,
                retry_after,
            )
            return retry_after

    LOGGER.warning(
        "Rate limited (HTTP 429)%s with no retry-after header, continuing in 1 second",
        label,
    )
    return 1


def resolve_project_id_by_name(
    rest_client: rest_api_client.OpikApi, project_name: str
) -> str:
    """
    Resolve a project name to its project ID.

    Args:
        rest_client: The REST API client instance.
        project_name: The name of the project.

    Returns:
        The project ID.

    Raises:
        ApiError: If the project is not found or if there's an API error.
    """
    project = rest_client.projects.retrieve_project(name=project_name)
    return project.id


def resolve_project_id_by_name_optional(
    rest_client: rest_api_client.OpikApi, project_name: Optional[str]
) -> Optional[str]:
    """
    Resolve the project ID associated with the given project name if provided, otherwise return None.

    This function attempts to resolve the ID of a project by its name using a REST client. If the
    provided project name is None, the function directly returns None without performing any resolution.

    Args:
        rest_client: A REST API client instance used to make requests
            to the backend API for project resolution.
        project_name: The name of the project for which the ID needs to be resolved,
            or None if no project name is specified.

    Returns:
        The resolved project ID as a string if the project name is provided and
            the resolution succeeds, or None otherwise.
    """
    if project_name is None:
        return None
    return resolve_project_id_by_name(rest_client, project_name)
