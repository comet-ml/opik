"""
Type annotations for boto3.resources.response module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any, Iterable

from boto3.resources.base import ServiceResource
from boto3.resources.factory import ResourceFactory
from boto3.resources.model import Parameter, ResponseResource
from boto3.utils import ServiceContext
from botocore.model import ServiceModel

def all_not_none(iterable: Iterable[Any]) -> bool: ...
def build_identifiers(
    identifiers: list[Parameter],
    parent: ServiceResource,
    params: dict[str, Any] | None = ...,
    raw_response: dict[str, Any] | None = ...,
) -> list[tuple[str, Any]]: ...
def build_empty_response(
    search_path: str, operation_name: str, service_model: ServiceModel
) -> dict[str, Any] | list[Any] | None: ...

class RawHandler:
    def __init__(self, search_path: str) -> None: ...
    def __call__(
        self, parent: ServiceResource, params: dict[str, Any], response: dict[str, Any]
    ) -> dict[str, Any]: ...

class ResourceHandler:
    def __init__(
        self,
        search_path: str,
        factory: ResourceFactory,
        resource_model: ResponseResource,
        service_context: ServiceContext,
        operation_name: str | None = ...,
    ) -> None: ...
    def __call__(
        self, parent: ServiceResource, params: dict[str, Any], response: dict[str, Any]
    ) -> ServiceResource | list[ServiceResource]: ...
    def handle_response_item(
        self,
        resource_cls: type[ServiceResource],
        parent: ServiceResource,
        identifiers: dict[str, Any],
        resource_data: dict[str, Any] | None,
    ) -> ServiceResource: ...
