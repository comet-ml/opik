"""
Type annotations for botocore.translate module.

Copyright 2025 Vlad Emelianov
"""

from collections.abc import Mapping
from typing import Any

from botocore.utils import merge_dicts as merge_dicts

def build_retry_config(
    endpoint_prefix: str,
    retry_model: Mapping[str, Any],
    definitions: Mapping[str, Any],
    client_retry_config: Mapping[str, Any] | None = ...,
) -> Any: ...
def resolve_references(config: Mapping[str, Any], definitions: Mapping[str, Any]) -> None: ...
