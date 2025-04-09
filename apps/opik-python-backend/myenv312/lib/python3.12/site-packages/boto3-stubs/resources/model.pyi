"""
Type annotations for boto3.resources.model module.

Copyright 2024 Vlad Emelianov
"""

import logging
from typing import Any, Literal, TypedDict

from botocore.model import Shape

logger: logging.Logger

class _ActionDefinition(TypedDict, total=False):
    request: dict[str, Any]
    resource: dict[str, Any]
    path: str

class _DefinitionWithParamsDefinition(TypedDict, total=False):
    params: list[dict[str, Any]]

class _RequestDefinition(TypedDict, total=False):
    operation: str

class _WaiterDefinition(TypedDict, total=False):
    waiterName: str

class _ResponseResourceDefinition(TypedDict, total=False):
    type: str
    path: str

class _ResourceModelDefinition(TypedDict, total=False):
    shape: str

class Identifier:
    def __init__(self, name: str, member_name: str | None = ...) -> None:
        self.name: str
        self.member_name: str

class Action:
    def __init__(
        self, name: str, definition: _ActionDefinition, resource_defs: dict[str, dict[str, Any]]
    ) -> None:
        self.name: str
        self.request: Request | None
        self.resource: ResponseResource | None
        self.path: str | None

class DefinitionWithParams:
    def __init__(self, definition: _DefinitionWithParamsDefinition) -> None: ...
    @property
    def params(self) -> list[Parameter]: ...

class Parameter:
    def __init__(
        self,
        target: str,
        source: str,
        name: str | None = ...,
        path: str | None = ...,
        value: str | float | bool | None = ...,
        **kwargs: Any,
    ) -> None:
        self.target: str
        self.source: str
        self.name: str | None
        self.path: str | None
        self.value: str | int | float | bool | None

class Request(DefinitionWithParams):
    def __init__(self, definition: _RequestDefinition) -> None:
        self.operation: str

class Waiter(DefinitionWithParams):
    PREFIX: Literal["WaitUntil"]
    def __init__(self, name: str, definition: _WaiterDefinition) -> None:
        self.name: str
        self.waiter_name: str

class ResponseResource:
    def __init__(
        self, definition: _ResponseResourceDefinition, resource_defs: dict[str, dict[str, Any]]
    ) -> None:
        self.type: str
        self.path: str

    @property
    def identifiers(self) -> list[Identifier]: ...
    @property
    def model(self) -> ResourceModel: ...

class Collection(Action):
    @property
    def batch_actions(self) -> list[Action]: ...

class ResourceModel:
    def __init__(
        self,
        name: str,
        definition: _ResourceModelDefinition,
        resource_defs: dict[str, dict[str, Any]],
    ) -> None:
        self.name: str
        self.shape: str | None

    def load_rename_map(self, shape: Shape | None = ...) -> None: ...
    def get_attributes(self, shape: Shape) -> dict[str, tuple[str, Shape]]: ...
    @property
    def identifiers(self) -> list[Identifier]: ...
    @property
    def load(self) -> Action | None: ...
    @property
    def actions(self) -> list[Action]: ...
    @property
    def batch_actions(self) -> list[Action]: ...
    @property
    def subresources(self) -> list[ResponseResource]: ...
    @property
    def references(self) -> list[Action]: ...
    @property
    def collections(self) -> list[Collection]: ...
    @property
    def waiters(self) -> list[Waiter]: ...
