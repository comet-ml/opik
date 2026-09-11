from .. import rest_api
from . import public_methods_patcher, retry_decorator


def configure(rest_client: rest_api.OpikApi) -> None:
    _configure_retries(rest_client)


def _configure_retries(rest_client: rest_api.OpikApi) -> None:
    domain_client_names = [
        "attachments",
        "datasets",
        "experiments",
        "traces",
        "spans",
        "projects",
        "prompts",
    ]
    for domain_client_name in domain_client_names:
        public_methods_patcher.patch(
            getattr(rest_client, domain_client_name), retry_decorator.opik_rest_retry
        )
