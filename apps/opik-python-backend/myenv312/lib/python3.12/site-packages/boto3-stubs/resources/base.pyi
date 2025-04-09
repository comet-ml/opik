"""
Type annotations for boto3.resources.base module.

Copyright 2024 Vlad Emelianov
"""

import logging
from typing import Any, TypeVar

from boto3.resources.model import ResourceModel
from botocore.client import BaseClient

logger: logging.Logger

_ResourceMeta = TypeVar("_ResourceMeta")

class ResourceMeta:
    client: BaseClient
    def __init__(
        self,
        service_name: str,
        identifiers: list[str] | None = ...,
        client: BaseClient | None = ...,
        data: dict[str, Any] | None = ...,
        resource_model: ResourceModel | None = ...,
    ) -> None:
        self.service_name: str
        self.identifiers: list[str]
        self.data: dict[str, Any]
        self.resource_model: ResourceModel

    def __eq__(self, other: object) -> bool: ...
    def copy(self: _ResourceMeta) -> _ResourceMeta: ...

class ServiceResource:
    meta: ResourceMeta = ...  # type: ignore

    def __init__(self, *args: Any, client: BaseClient | None = ..., **kwargs: Any) -> None: ...
    def __eq__(self, other: object) -> bool: ...
    def __hash__(self) -> int: ...
