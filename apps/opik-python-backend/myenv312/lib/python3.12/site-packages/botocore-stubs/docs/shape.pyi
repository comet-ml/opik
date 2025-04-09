"""
Type annotations for botocore.docs.shape module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Mapping

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import Shape

class ShapeDocumenter:
    EVENT_NAME: str = ...
    def __init__(
        self,
        service_name: str,
        operation_name: str,
        event_emitter: BaseEventHooks,
        context: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def traverse_and_document_shape(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Any = ...,
        exclude: Any = ...,
        name: str | None = ...,
        is_required: bool = ...,
    ) -> None: ...
