"""
Type annotations for botocore.compat module.

Copyright 2025 Vlad Emelianov
"""

import datetime
from base64 import encodebytes as encodebytes
from collections import OrderedDict as OrderedDict
from email.utils import formatdate as formatdate
from hashlib import _Hash
from http.client import HTTPMessage
from http.client import HTTPResponse as HTTPResponse
from inspect import FullArgSpec
from itertools import zip_longest as zip_longest
from logging import Logger
from typing import Any, Callable, Iterable, Mapping, Pattern, TypeVar
from urllib.parse import parse_qs as parse_qs
from urllib.parse import parse_qsl as parse_qsl
from urllib.parse import quote as quote
from urllib.parse import unquote as unquote
from urllib.parse import unquote_plus
from urllib.parse import urlencode as urlencode
from urllib.parse import urljoin as urljoin
from urllib.parse import urlparse as urlparse
from urllib.parse import urlsplit as urlsplit
from urllib.parse import urlunsplit as urlunsplit
from xml.etree import ElementTree as ETree

from botocore.exceptions import MD5UnavailableError as MD5UnavailableError

logger: Logger = ...

_R = TypeVar("_R")

class HTTPHeaders(HTTPMessage):
    @classmethod
    def from_dict(cls: type[_R], d: Mapping[str, Any]) -> _R: ...
    @classmethod
    def from_pairs(cls: type[_R], pairs: Iterable[tuple[str, Any]]) -> _R: ...

file_type: Any
zip: Any
unquote_str = unquote_plus

def set_socket_timeout(http_response: Any, timeout: Any) -> None: ...
def accepts_kwargs(func: Callable[..., Any]) -> FullArgSpec: ...
def ensure_unicode(s: str, encoding: str | None = ..., errors: Any | None = ...) -> str: ...
def ensure_bytes(s: str | bytes, encoding: str = ..., errors: str = ...) -> bytes: ...

XMLParseError = ETree.ParseError

def filter_ssl_warnings() -> None: ...
def _from_dict(cls: type[_R], d: Mapping[str, Any]) -> _R: ...

from_dict: Any

def _from_pairs(cls: type[_R], d: Mapping[str, Any]) -> _R: ...

from_pairs: Any

def copy_kwargs(kwargs: _R) -> _R: ...
def total_seconds(delta: datetime.timedelta) -> float: ...

MD5_AVAILABLE: bool

def get_md5(*args: Any, **kwargs: Any) -> _Hash: ...
def compat_shell_split(s: str, platform: str | None = ...) -> list[str]: ...
def get_tzinfo_options() -> tuple[Any, ...]: ...

HAS_CRT: bool
disabled: str

def has_minimum_crt_version(minimum_version: tuple[int, ...]) -> bool: ...

IPV4_PAT: str
IPV4_RE: Pattern[str]
HEX_PAT: str
LS32_PAT: str
UNRESERVED_PAT: str
IPV6_PAT: str
ZONE_ID_PAT: str
IPV6_ADDRZ_PAT: str
IPV6_ADDRZ_RE: Pattern[str]
UNSAFE_URL_CHARS: frozenset[str]
HAS_GZIP: bool
