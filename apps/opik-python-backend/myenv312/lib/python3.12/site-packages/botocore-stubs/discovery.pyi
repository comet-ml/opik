"""
Type annotations for botocore.discovery module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any, Callable, Mapping

from botocore.client import BaseClient
from botocore.exceptions import BotoCoreError as BotoCoreError
from botocore.exceptions import ConnectionError as ConnectionError
from botocore.exceptions import HTTPClientError as HTTPClientError
from botocore.hooks import BaseEventHooks
from botocore.model import OperationModel, ServiceModel
from botocore.model import OperationNotFoundError as OperationNotFoundError
from botocore.utils import CachedProperty as CachedProperty

logger: Logger = ...

class EndpointDiscoveryException(BotoCoreError): ...

class EndpointDiscoveryRequired(EndpointDiscoveryException):
    fmt: str = ...

class EndpointDiscoveryRefreshFailed(EndpointDiscoveryException):
    fmt: str = ...

def block_endpoint_discovery_required_operations(model: OperationModel, **kwargs: Any) -> None: ...

class EndpointDiscoveryModel:
    def __init__(self, service_model: ServiceModel) -> None: ...
    @CachedProperty
    def discovery_operation_name(self) -> str: ...
    @CachedProperty
    def discovery_operation_keys(self) -> list[str]: ...
    def discovery_required_for(self, operation_name: str) -> bool: ...
    def discovery_operation_kwargs(self, **kwargs: Any) -> dict[str, Any]: ...
    def gather_identifiers(
        self, operation: OperationModel, params: Mapping[str, Any]
    ) -> dict[str, Any]: ...

class EndpointDiscoveryManager:
    def __init__(
        self,
        client: BaseClient,
        cache: Any | None = ...,
        current_time: Callable[[], float] | None = ...,
        always_discover: bool = ...,
    ) -> None: ...
    def gather_identifiers(self, operation: OperationModel, params: Mapping[str, Any]) -> Any: ...
    def delete_endpoints(self, **kwargs: Any) -> None: ...
    def describe_endpoint(self, **kwargs: Any) -> Any: ...

class EndpointDiscoveryHandler:
    def __init__(self, manager: EndpointDiscoveryManager) -> None: ...
    def register(self, events: BaseEventHooks, service_id: str) -> None: ...
    def gather_identifiers(
        self,
        params: Mapping[str, Any],
        model: OperationModel,
        context: Mapping[str, Any],
        **kwargs: Any,
    ) -> None: ...
    def discover_endpoint(self, request: Any, operation_name: str, **kwargs: Any) -> None: ...
    def handle_retries(
        self,
        request_dict: Mapping[str, Any],
        response: Mapping[str, Any],
        operation: OperationModel,
        **kwargs: Any,
    ) -> Any: ...
