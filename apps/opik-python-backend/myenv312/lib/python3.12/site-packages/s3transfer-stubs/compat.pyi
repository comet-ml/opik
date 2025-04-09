"""
Type annotations for s3transfer.compat module.

Copyright 2025 Vlad Emelianov
"""

import os
from multiprocessing.managers import BaseManager as _BaseManager
from typing import IO, Any, Callable

rename_file = os.rename

class BaseManager(_BaseManager): ...

def accepts_kwargs(func: Callable[..., Any]) -> bool: ...

SOCKET_ERROR: type[ConnectionError]
MAXINT: None

def seekable(fileobj: IO[Any] | str | bytes) -> bool: ...
def readable(fileobj: IO[Any] | str | bytes) -> bool: ...
def fallocate(fileobj: IO[Any] | str | bytes, size: int) -> None: ...
