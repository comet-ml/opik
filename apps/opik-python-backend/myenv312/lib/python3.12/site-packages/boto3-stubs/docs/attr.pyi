"""
Type annotations for boto3.docs.attr module.

Copyright 2024 Vlad Emelianov
"""

from boto3.resources.model import Identifier
from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.docs.params import ResponseParamsDocumenter
from botocore.hooks import BaseEventHooks
from botocore.model import Shape

class ResourceShapeDocumenter(ResponseParamsDocumenter):
    EVENT_NAME: str

def document_attribute(
    section: DocumentStructure,
    service_name: str,
    resource_name: str,
    attr_name: str,
    event_emitter: BaseEventHooks,
    attr_model: Shape,
    include_signature: bool = True,
) -> None: ...
def document_identifier(
    section: DocumentStructure,
    resource_name: str,
    identifier_model: Identifier,
    include_signature: bool = ...,
) -> None: ...
def document_reference(
    section: DocumentStructure, reference_model: Shape, include_signature: bool = ...
) -> None: ...
