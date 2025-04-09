"""
Type annotations for botocore.docs.waiter module.

Copyright 2025 Vlad Emelianov
"""

from botocore.client import BaseClient
from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import ServiceModel
from botocore.waiter import WaiterModel

class WaiterDocumenter:
    def __init__(
        self, client: BaseClient, service_waiter_model: WaiterModel, root_docs_path: str
    ) -> None: ...
    def document_waiters(self, section: DocumentStructure) -> None: ...

def document_wait_method(
    section: DocumentStructure,
    waiter_name: str,
    event_emitter: BaseEventHooks,
    service_model: ServiceModel,
    service_waiter_model: WaiterModel,
    include_signature: bool = ...,
) -> None: ...
