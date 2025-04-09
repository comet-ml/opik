"""
Type annotations for botocore.context module.

Copyright 2025 Vlad Emelianov
"""

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from typing import TypeVar

from typing_extensions import ParamSpec

_Param = ParamSpec("_Param")
_R = TypeVar("_R")

@dataclass
class ClientContext:
    features: set[str] = ...

_context: ContextVar[ClientContext] = ...

def get_context() -> ClientContext | None:
    return _context.get(None)

def set_context(ctx: ClientContext) -> Token[ClientContext]: ...
def reset_context(token: Token[ClientContext]) -> None: ...
@contextmanager
def start_as_current_context(ctx: ClientContext | None = ...) -> Iterator[None]: ...
def with_current_context(
    hook: Callable[[], None] | None = ...,
) -> Callable[[Callable[_Param, _R]], Callable[_Param, _R]]: ...
