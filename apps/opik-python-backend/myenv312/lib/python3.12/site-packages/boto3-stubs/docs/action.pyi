"""
Type annotations for boto3.docs.action module.

Copyright 2024 Vlad Emelianov
"""

from boto3.resources.model import Action
from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks
from botocore.model import ServiceModel

from .base import NestedDocumenter

PUT_DATA_WARNING_MESSAGE: str
WARNING_MESSAGES: dict[str, dict[str, str]]
IGNORE_PARAMS: dict[str, dict[str, list[str]]]

class ActionDocumenter(NestedDocumenter):
    def document_actions(self, section: DocumentStructure) -> None: ...

def document_action(
    section: DocumentStructure,
    resource_name: str,
    event_emitter: BaseEventHooks,
    action_model: Action,
    service_model: ServiceModel,
    include_signature: bool = ...,
) -> None: ...
def document_load_reload_action(
    section: DocumentStructure,
    action_name: str,
    resource_name: str,
    event_emitter: BaseEventHooks,
    load_model: Action,
    service_model: ServiceModel,
    include_signature: bool = ...,
) -> None: ...
