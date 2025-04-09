"""
Type annotations for botocore.docs.method module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Sequence

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import OperationModel

AWS_DOC_BASE: str = ...

def get_instance_public_methods(instance: Any) -> dict[str, Any]: ...
def document_model_driven_signature(
    section: DocumentStructure,
    name: str,
    operation_model: OperationModel,
    include: Sequence[str] | None = ...,
    exclude: Sequence[str] | None = ...,
) -> None: ...
def document_custom_signature(
    section: DocumentStructure,
    name: str,
    method: Any,
    include: Sequence[str] | None = ...,
    exclude: Sequence[str] | None = ...,
) -> None: ...
def document_custom_method(section: DocumentStructure, method_name: str, method: Any) -> None: ...
def document_model_driven_method(
    section: DocumentStructure,
    method_name: str,
    operation_model: OperationModel,
    event_emitter: BaseEventHooks,
    method_description: str | None = ...,
    example_prefix: str | None = ...,
    include_input: dict[str, Any] | None = ...,
    include_output: dict[str, Any] | None = ...,
    exclude_input: Sequence[str] | None = ...,
    exclude_output: Sequence[str] | None = ...,
    document_output: bool = ...,
    include_signature: bool = ...,
) -> None: ...
