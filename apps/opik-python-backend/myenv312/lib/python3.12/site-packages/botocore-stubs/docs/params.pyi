"""
Type annotations for botocore.docs.params module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Sequence

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.docs.shape import ShapeDocumenter
from botocore.model import Shape

class BaseParamsDocumenter(ShapeDocumenter):
    def document_params(
        self,
        section: DocumentStructure,
        shape: Shape,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
    ) -> None: ...
    def document_recursive_shape(
        self, section: DocumentStructure, shape: Shape, **kwargs: Any
    ) -> None: ...
    def document_shape_default(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_list(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_map(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_structure(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
        name: str | None = ...,
        **kwargs: Any,
    ) -> None: ...

class ResponseParamsDocumenter(BaseParamsDocumenter):
    EVENT_NAME: str = ...
    def document_shape_type_event_stream(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        **kwargs: Any,
    ) -> None: ...

class RequestParamsDocumenter(BaseParamsDocumenter):
    EVENT_NAME: str = ...
    def document_shape_type_structure(  # type: ignore[override]
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: Sequence[str] | None = ...,
        exclude: Sequence[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
