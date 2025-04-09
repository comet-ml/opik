"""
Type annotations for boto3.resources.params module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any, Pattern

from boto3.resources.base import ServiceResource
from boto3.resources.model import Request

INDEX_RE: Pattern[str]

def get_data_member(parent: ServiceResource, path: str) -> dict[str, Any] | None: ...
def create_request_parameters(
    parent: ServiceResource,
    request_model: Request,
    params: dict[str, Any] | None = ...,
    index: int | None = ...,
) -> dict[str, Any]: ...
def build_param_structure(
    params: dict[str, Any], target: str, value: Any, index: int | None = ...
) -> None: ...
