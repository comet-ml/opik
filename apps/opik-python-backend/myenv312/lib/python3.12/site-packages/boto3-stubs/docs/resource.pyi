"""
Type annotations for boto3.docs.resource module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.session import Session

from .base import BaseDocumenter

class ResourceDocumenter(BaseDocumenter):
    def __init__(self, resource: Any, botocore_session: Session, root_docs_path: str) -> None: ...
    def document_resource(self, section: DocumentStructure) -> None: ...

class ServiceResourceDocumenter(ResourceDocumenter):
    @property
    def class_name(self) -> str: ...
