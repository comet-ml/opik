import logging
import time
from typing import Any, Callable, Optional

from ..rest_api import client as rest_api_client
from ..rest_api.core.api_error import ApiError
from ..rate_limit import rate_limit

LOGGER = logging.getLogger(__name__)


def ensure_rest_api_call_respecting_rate_limit(
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

                LOGGER.info(
                    "Rate limited (HTTP 429) with no retry-after header, retrying in 1 second"
                )
                time.sleep(1)
                continue

            raise


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
