"""
Type annotations for botocore.docs.sharedexample module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Iterable, Mapping

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.model import OperationModel, Shape

class SharedExampleDocumenter:
    def document_shared_example(
        self, example: Any, prefix: str, section: DocumentStructure, operation_model: OperationModel
    ) -> None: ...
    def document_input(
        self, section: DocumentStructure, example: Mapping[str, Any], prefix: str, shape: Shape
    ) -> None: ...
    def document_output(
        self, section: DocumentStructure, example: Mapping[str, Any], shape: Shape
    ) -> None: ...

def document_shared_examples(
    section: DocumentStructure,
    operation_model: OperationModel,
    example_prefix: str,
    shared_examples: Iterable[Mapping[str, Any]],
) -> None: ...
