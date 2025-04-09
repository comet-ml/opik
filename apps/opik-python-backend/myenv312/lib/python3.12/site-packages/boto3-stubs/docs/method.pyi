"""
Type annotations for boto3.docs.method module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

from boto3.resources.model import Action
from botocore.docs.bcdoc.restdoc import DocumentStructure
from botocore.hooks import BaseEventHooks

def document_model_driven_resource_method(
    section: DocumentStructure,
    method_name: str,
    operation_model: Any,
    event_emitter: BaseEventHooks,
    method_description: str | None = ...,
    example_prefix: str | None = ...,
    include_input: Any | None = ...,
    include_output: Any | None = ...,
    exclude_input: Any | None = ...,
    exclude_output: Any | None = ...,
    document_output: bool = ...,
    resource_action_model: Action | None = ...,
    include_signature: bool = ...,
) -> None: ...
