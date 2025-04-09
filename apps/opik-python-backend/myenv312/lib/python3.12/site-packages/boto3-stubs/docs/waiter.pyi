"""
Type annotations for boto3.docs.waiter module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import ServiceModel
from botocore.waiter import WaiterModel

from .base import NestedDocumenter

class WaiterResourceDocumenter(NestedDocumenter):
    def __init__(
        self,
        resource: Any,
        service_waiter_model: WaiterModel,
        root_docs_path: str,
    ) -> None: ...
    def document_resource_waiters(self, section: DocumentStructure) -> None: ...

def document_resource_waiter(
    section: DocumentStructure,
    resource_name: str,
    event_emitter: BaseEventHooks,
    service_model: ServiceModel,
    resource_waiter_model: Any,
    service_waiter_model: WaiterModel,
    include_signature: bool = ...,
) -> None: ...
