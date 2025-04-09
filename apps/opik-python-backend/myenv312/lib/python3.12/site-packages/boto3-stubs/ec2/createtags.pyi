"""
Type annotations for boto3.ec2.createtags module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any, Iterable

def inject_create_tags(
    event_name: str, class_attributes: dict[str, Any], **kwargs: Any
) -> None: ...
def create_tags(self: Any, **kwargs: Iterable[Any]) -> list[dict[str, Any]]: ...
