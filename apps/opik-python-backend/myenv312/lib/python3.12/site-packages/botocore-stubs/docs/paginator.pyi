"""
Type annotations for botocore.docs.paginator module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any

from botocore.client import BaseClient
from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import ServiceModel

class PaginatorDocumenter:
    def __init__(
        self, client: BaseClient, service_paginator_model: ServiceModel, root_docs_path: str
    ) -> None: ...
    def document_paginators(self, section: DocumentStructure) -> None: ...

def document_paginate_method(
    section: DocumentStructure,
    paginator_name: str,
    event_emitter: BaseEventHooks,
    service_model: ServiceModel,
    paginator_config: Any,
    include_signature: bool = ...,
) -> None: ...
