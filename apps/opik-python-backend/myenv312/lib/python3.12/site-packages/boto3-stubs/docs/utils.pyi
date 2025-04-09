"""
Type annotations for boto3.docs.utils module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any, Iterable, Mapping

from botocore.docs.bcdoc.restdoc import DocumentStructure

def get_resource_ignore_params(params: Mapping[str, Any]) -> list[str]: ...
def is_resource_action(action_handle: Any) -> bool: ...
def get_resource_public_actions(resource_class: Any) -> list[Any]: ...
def get_identifier_values_for_example(identifier_names: Iterable[str]) -> str: ...
def get_identifier_args_for_signature(identifier_names: Iterable[str]) -> str: ...
def get_identifier_description(resource_name: str, identifier_name: str) -> str: ...
def add_resource_type_overview(
    section: DocumentStructure,
    resource_type: str,
    description: str,
    intro_link: str | None = ...,
) -> None: ...

class DocumentModifiedShape:
    def __init__(
        self, shape_name: str, new_type: str, new_description: str, new_example_value: str
    ) -> None: ...
    def replace_documentation_for_matching_shape(
        self, event_name: str, section: DocumentStructure, **kwargs: Any
    ) -> None: ...
