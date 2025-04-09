"""
Type annotations for botocore module.

Copyright 2025 Vlad Emelianov
"""

import logging
from typing import Any, Callable, Mapping

from botocore.session import Session

class NullHandler(logging.Handler):
    def emit(self, record: Any) -> None: ...

log: logging.Logger
ScalarTypes: tuple[str, ...]
BOTOCORE_ROOT: str

UNSIGNED: Any
__version__: str

def xform_name(name: str, sep: str = ..., _xform_cache: Mapping[str, str] = ...) -> str: ...
def register_initializer(callback: Callable[[Session], None]) -> None: ...
def unregister_initializer(callback: Callable[[Session], None]) -> None: ...
def invoke_initializers(session: Session) -> None: ...
