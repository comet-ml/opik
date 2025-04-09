"""
Type annotations for boto3.docs.service module.

Copyright 2024 Vlad Emelianov
"""

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.docs.service import ServiceDocumenter as BaseServiceDocumenter
from botocore.session import Session

class ServiceDocumenter(BaseServiceDocumenter):
    EXAMPLE_PATH: str
    sections: list[str]
    def __init__(self, service_name: str, session: Session, root_docs_path: str) -> None: ...
    def document_service(self) -> bytes: ...
    def client_api(self, section: DocumentStructure) -> None: ...
    def resource_section(self, section: DocumentStructure) -> None: ...
