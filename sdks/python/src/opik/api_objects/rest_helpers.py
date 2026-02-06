import logging
import time
from typing import Any, Callable

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
