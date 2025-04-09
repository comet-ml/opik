"""
Type annotations for botocore.docs.utils module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.model import ServiceModel

def py_type_name(type_name: str) -> str: ...
def py_default(type_name: str) -> str: ...
def get_official_service_name(service_model: ServiceModel) -> str: ...

_DocumentedShape: Any = ...

class DocumentedShape(_DocumentedShape):
    def __new__(
        cls,
        name: str,
        type_name: str,
        documentation: Any,
        metadata: Any = ...,
        members: Any = ...,
        required_members: Any = ...,
    ) -> Any: ...

class AutoPopulatedParam:
    def __init__(self, name: str, param_description: str | None = ...) -> None: ...
    def document_auto_populated_param(
        self, event_name: str, section: DocumentStructure, **kwargs: Any
    ) -> None: ...

class HideParamFromOperations:
    def __init__(
        self, service_name: str, parameter_name: str, operation_names: list[str]
    ) -> None: ...
    def hide_param(self, event_name: str, section: DocumentStructure, **kwargs: Any) -> None: ...

class AppendParamDocumentation:
    def __init__(self, parameter_name: str, doc_string: str) -> None: ...
    def append_documentation(
        self, event_name: str, section: DocumentStructure, **kwargs: Any
    ) -> None: ...

def escape_controls(value: str) -> str: ...
