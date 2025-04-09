"""
Type annotations for boto3.dynamodb.transform module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any, Callable

from boto3.dynamodb.conditions import ConditionExpressionBuilder
from boto3.dynamodb.types import TypeDeserializer, TypeSerializer
from boto3.resources.model import ResourceModel
from botocore.model import Shape

def register_high_level_interface(base_classes: list[Any], **kwargs: Any) -> None: ...
def copy_dynamodb_params(params: Any, **kwargs: Any) -> Any: ...

class DynamoDBHighLevelResource:
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...

class TransformationInjector:
    def __init__(
        self,
        transformer: ParameterTransformer | None = ...,
        condition_builder: ConditionExpressionBuilder | None = ...,
        serializer: TypeSerializer | None = ...,
        deserializer: TypeDeserializer | None = ...,
    ) -> None: ...
    def inject_condition_expressions(
        self, params: dict[str, Any], model: ResourceModel
    ) -> None: ...
    def inject_attribute_value_input(
        self, params: dict[str, Any], model: ResourceModel
    ) -> None: ...
    def inject_attribute_value_output(
        self, parsed: dict[str, Any], model: ResourceModel
    ) -> None: ...

class ConditionExpressionTransformation:
    def __init__(
        self,
        condition_builder: ConditionExpressionBuilder,
        placeholder_names: list[str],
        placeholder_values: list[str],
        is_key_condition: bool = ...,
    ) -> None: ...
    def __call__(self, value: Any) -> Any: ...

class ParameterTransformer:
    def transform(
        self,
        params: dict[str, Any],
        model: Shape,
        transformation: Callable[[Any], Any],
        target_shape: str,
    ) -> None: ...
