"""
Type annotations for boto3.dynamodb.table module.

Copyright 2024 Vlad Emelianov
"""

import logging
from types import TracebackType
from typing import Any, TypeVar

from botocore.client import BaseClient

logger: logging.Logger

def register_table_methods(base_classes: list[Any], **kwargs: Any) -> None: ...

class TableResource:
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...
    def batch_writer(self, overwrite_by_pkeys: list[str] | None = ...) -> BatchWriter: ...

_R = TypeVar("_R")

class BatchWriter:
    def __init__(
        self,
        table_name: str,
        client: BaseClient,
        flush_amount: int = ...,
        overwrite_by_pkeys: list[str] | None = ...,
    ) -> None: ...
    def put_item(self, Item: dict[str, Any]) -> None: ...
    def delete_item(self, Key: dict[str, Any]) -> None: ...
    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        tb: TracebackType | None,
    ) -> None: ...
