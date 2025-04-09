"""
Type annotations for boto3.dynamodb.types module.

Copyright 2024 Vlad Emelianov
"""

from decimal import Context
from typing import Any, Literal, Mapping, Sequence, TypedDict

class _AttributeValueTypeDef(TypedDict, total=False):
    S: str
    N: str
    B: bytes
    SS: Sequence[str]
    NS: Sequence[str]
    BS: Sequence[bytes]
    M: Mapping[str, Any]
    L: Sequence[Any]
    NULL: bool
    BOOL: bool

STRING: Literal["S"]
NUMBER: Literal["N"]
BINARY: Literal["B"]
STRING_SET: Literal["SS"]
NUMBER_SET: Literal["NS"]
BINARY_SET: Literal["BS"]
NULL: Literal["NULL"]
BOOLEAN: Literal["BOOL"]
MAP: Literal["M"]
LIST: Literal["L"]

DYNAMODB_CONTEXT: Context

BINARY_TYPES: tuple[Any, ...]

class Binary:
    def __init__(self, value: Any) -> None: ...
    def __eq__(self, other: object) -> bool: ...
    def __ne__(self, other: object) -> bool: ...
    def __bytes__(self) -> str: ...
    def __hash__(self) -> int: ...

class TypeSerializer:
    def serialize(self, value: Any) -> _AttributeValueTypeDef: ...

class TypeDeserializer:
    def deserialize(self, value: _AttributeValueTypeDef) -> Any: ...
