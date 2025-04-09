"""
Type annotations for boto3.resources.action module.

Copyright 2024 Vlad Emelianov
"""

import logging
from typing import Any, Callable, Sequence

from boto3.resources.base import ServiceResource
from boto3.resources.collection import ResourceCollection
from boto3.resources.factory import ResourceFactory
from boto3.resources.model import Action, Waiter
from boto3.utils import ServiceContext
from botocore.hooks import BaseEventHooks

logger: logging.Logger

class ServiceAction:
    def __init__(
        self,
        action_model: Action,
        factory: ResourceFactory | None = ...,
        service_context: ServiceContext | None = ...,
    ) -> None: ...
    def __call__(
        self, parent: ServiceResource, *args: Any, **kwargs: Any
    ) -> ServiceResource | list[ServiceResource] | dict[str, Any]: ...

class BatchAction(ServiceAction):
    def __call__(  # type: ignore [override]
        self, parent: ResourceCollection, *args: Any, **kwargs: Any
    ) -> list[dict[str, Any]]: ...

class WaiterAction:
    def __init__(self, waiter_model: Waiter, waiter_resource_name: str) -> None: ...
    def __call__(self, parent: ServiceResource, *args: Any, **kwargs: Any) -> None: ...

class CustomModeledAction:
    def __init__(
        self,
        action_name: str,
        action_model: dict[str, Any],
        function: Callable[..., Any],
        event_emitter: BaseEventHooks,
    ) -> None: ...
    def inject(
        self,
        class_attributes: Sequence[Any],
        service_context: ServiceContext,
        event_name: str,
        **kwargs: Any,
    ) -> None: ...
