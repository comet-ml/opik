"""
Type annotations for botocore.useragent module.

Copyright 2025 Vlad Emelianov
"""

import logging
from typing import Any, NamedTuple, TypeVar

from botocore.awsrequest import AWSRequest
from botocore.config import Config

_R = TypeVar("_R")

logger: logging.Logger = ...

def register_feature_id(feature_id: str) -> None: ...
def sanitize_user_agent_string_component(raw_str: str, allow_hash: bool) -> str: ...

class UserAgentComponentSizeConfig:
    def __init__(self, max_size_in_bytes: int, delimiter: str) -> None: ...

class UserAgentComponent(NamedTuple):
    prefix: str
    name: str
    value: str | None = ...
    size_config: UserAgentComponentSizeConfig | None = ...

    def to_string(self) -> str: ...

class RawStringUserAgentComponent:
    def __init__(self, value: str) -> None: ...
    def to_string(self) -> str: ...

def modify_components(components: list[Any]) -> list[Any]: ...

class UserAgentString:
    def __init__(
        self,
        platform_name: str,
        platform_version: str,
        platform_machine: str,
        python_version: str,
        python_implementation: str,
        execution_env: str,
        crt_version: str | None = ...,
    ) -> None: ...
    @classmethod
    def from_environment(cls: type[_R]) -> _R: ...
    def set_session_config(
        self: _R,
        session_user_agent_name: str,
        session_user_agent_version: str,
        session_user_agent_extra: str,
    ) -> _R: ...
    def set_client_features(self, features: set[str]) -> None: ...
    def with_client_config(self: _R, client_config: Config) -> _R: ...
    def to_string(self) -> str: ...
    def rebuild_and_replace_user_agent_handler(
        self, operation_name: str, request: AWSRequest, **kwargs: Any
    ) -> None: ...
