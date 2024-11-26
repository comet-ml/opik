import tenacity
import httpx
from . import rest_api
from typing import Callable, Any

connection_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_exponential(multiplier=1, min=1, max=10),
    retry=tenacity.retry_if_exception_type(
        (
            httpx.RemoteProtocolError,  # handle retries for expired connections
            httpx.ConnectError,
            httpx.ConnectTimeout,
        )
    ),
)


def configure(rest_client: rest_api.OpikApi) -> None:
    _configure_retries(rest_client)


def _configure_retries(rest_client: rest_api.OpikApi) -> None:
    domain_client_names = [
        "datasets",
        "experiments",
        "traces",
        "spans",
        "projects",
        "prompts",
    ]
    for domain_client_name in domain_client_names:
        _decorate_public_instance_methods(
            getattr(rest_client, domain_client_name), connection_retry
        )
    pass


def _decorate_public_instance_methods(
    instance: Any, decorator: Callable[[Callable], Callable]
) -> None:
    attr_name: str
    for attr_name in instance.__class__.__dict__.keys():
        attr_value = getattr(instance, attr_name)
        if callable(attr_value) and not attr_name.startswith("_"):
            decorated_method = decorator(attr_value)
            setattr(instance, attr_name, decorated_method)
