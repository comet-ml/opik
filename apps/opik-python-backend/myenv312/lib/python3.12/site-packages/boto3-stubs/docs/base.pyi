"""
Type annotations for boto3.docs.base module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

class BaseDocumenter:
    member_map: dict[str, Any]
    represents_service_resource: Any
    def __init__(self, resource: Any) -> None: ...
    @property
    def class_name(self) -> str: ...

class NestedDocumenter(BaseDocumenter):
    def __init__(self, resource: Any, root_docs_path: str) -> None: ...
    @property
    def class_name(self) -> str: ...
