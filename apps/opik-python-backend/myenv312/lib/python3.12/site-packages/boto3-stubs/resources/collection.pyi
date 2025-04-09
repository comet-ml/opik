"""
Type annotations for boto3.resources.collection module.

Copyright 2024 Vlad Emelianov
"""

import logging
from typing import Any, Iterator, TypeVar

from boto3.resources.base import ServiceResource
from boto3.resources.factory import ResourceFactory
from boto3.resources.model import Collection
from boto3.resources.response import ResourceHandler
from boto3.utils import ServiceContext
from botocore.hooks import BaseEventHooks

logger: logging.Logger

_ResourceCollection = TypeVar("_ResourceCollection", bound=ResourceCollection)

class ResourceCollection:
    def __init__(
        self, model: Collection, parent: ServiceResource, handler: ResourceHandler, **kwargs: Any
    ) -> None: ...
    def __iter__(self) -> Iterator[Any]: ...
    def pages(self) -> Iterator[list[Any]]: ...
    def all(self: _ResourceCollection) -> _ResourceCollection: ...
    def filter(self: _ResourceCollection, **kwargs: Any) -> _ResourceCollection: ...
    def limit(self: _ResourceCollection, count: int) -> _ResourceCollection: ...
    def page_size(self: _ResourceCollection, count: int) -> _ResourceCollection: ...

class CollectionManager:
    def __init__(
        self,
        collection_model: Collection,
        parent: ServiceResource,
        factory: ResourceFactory,
        service_context: ServiceContext,
    ) -> None: ...
    def iterator(self, **kwargs: Any) -> ResourceCollection: ...
    def all(self) -> ResourceCollection: ...
    def filter(self, **kwargs: Any) -> ResourceCollection: ...
    def limit(self, count: int) -> ResourceCollection: ...
    def page_size(self, count: InterruptedError) -> ResourceCollection: ...
    def pages(self) -> list[ServiceResource]: ...

class CollectionFactory:
    def load_from_definition(
        self,
        resource_name: str,
        collection_model: Collection,
        service_context: ServiceContext,
        event_emitter: BaseEventHooks,
    ) -> CollectionManager: ...
