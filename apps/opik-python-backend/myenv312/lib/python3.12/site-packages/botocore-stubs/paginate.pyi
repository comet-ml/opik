"""
Type annotations for botocore.paginate module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any, Generic, Iterator, TypeVar

from botocore.exceptions import PaginationError as PaginationError
from botocore.model import OperationModel
from botocore.utils import merge_dicts as merge_dicts
from botocore.utils import set_value_from_jmespath as set_value_from_jmespath
from jmespath.parser import ParsedResult

_R = TypeVar("_R")
log: Logger = ...

class TokenEncoder:
    def encode(self, token: dict[str, Any]) -> str: ...

class TokenDecoder:
    def decode(self, token: str) -> dict[str, Any]: ...

class PaginatorModel:
    def __init__(self, paginator_config: Any) -> None: ...
    def get_paginator(self, operation_name: str) -> Any: ...

class PageIterator(Generic[_R]):
    def __init__(
        self,
        method: Any,
        input_token: Any,
        output_token: Any,
        more_results: Any,
        result_keys: Any,
        non_aggregate_keys: Any,
        limit_key: str,
        max_items: int,
        starting_token: Any,
        page_size: int,
        op_kwargs: dict[str, Any],
    ) -> None: ...
    @property
    def result_keys(self) -> list[ParsedResult] | None: ...
    @property
    def resume_token(self) -> dict[str, Any] | None: ...
    @resume_token.setter
    def resume_token(self, value: dict[str, Any] | None) -> None: ...
    @property
    def non_aggregate_part(self) -> dict[str, Any]: ...
    def __iter__(self) -> Iterator[_R]: ...
    def search(self, expression: str) -> Iterator[Any]: ...
    def result_key_iters(self) -> list[ResultKeyIterator[_R]]: ...
    def build_full_result(self) -> dict[str, Any]: ...

class Paginator(Generic[_R]):
    PAGE_ITERATOR_CLS: type[PageIterator[Any]] = ...
    def __init__(
        self,
        method: str,
        pagination_config: dict[str, Any],
        model: OperationModel,
    ) -> None: ...
    @property
    def result_keys(self) -> list[ParsedResult] | None: ...
    def paginate(self, **kwargs: Any) -> PageIterator[_R]: ...

class ResultKeyIterator(Generic[_R]):
    def __init__(self, pages_iterator: PageIterator[_R], result_key: ParsedResult) -> None:
        self.result_key: ParsedResult = ...

    def __iter__(self) -> Iterator[_R]: ...
