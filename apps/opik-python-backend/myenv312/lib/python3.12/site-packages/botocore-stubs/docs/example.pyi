"""
Type annotations for botocore.docs.example module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.docs.shape import ShapeDocumenter
from botocore.model import Shape

class BaseExampleDocumenter(ShapeDocumenter):
    def document_example(
        self,
        section: DocumentStructure,
        shape: Shape,
        prefix: str | None = ...,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
    ) -> None: ...
    def document_recursive_shape(
        self, section: DocumentStructure, shape: Shape, **kwargs: Any
    ) -> None: ...
    def document_shape_default(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_string(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_list(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_structure(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def document_shape_type_map(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...

class ResponseExampleDocumenter(BaseExampleDocumenter):
    EVENT_NAME: str = ...
    def document_shape_type_event_stream(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        **kwargs: Any,
    ) -> None: ...

class RequestExampleDocumenter(BaseExampleDocumenter):
    EVENT_NAME: str = ...
    def document_shape_type_structure(
        self,
        section: DocumentStructure,
        shape: Shape,
        history: Any,
        include: list[str] | None = ...,
        exclude: list[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
