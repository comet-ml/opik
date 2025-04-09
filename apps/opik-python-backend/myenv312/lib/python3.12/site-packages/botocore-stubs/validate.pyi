"""
Type annotations for botocore.validate module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Iterable, Mapping

from botocore.exceptions import ParamValidationError as ParamValidationError
from botocore.model import OperationModel, Shape
from botocore.serialize import Serializer
from botocore.utils import is_json_value_header as is_json_value_header
from botocore.utils import parse_to_aware_datetime as parse_to_aware_datetime

def validate_parameters(params: Mapping[str, Any], shape: Shape) -> None: ...
def type_check(valid_types: Iterable[Any]) -> Any: ...
def range_check(
    name: str, value: Any, shape: Shape, error_type: Any, errors: ValidationErrors
) -> None: ...

class ValidationErrors:
    def __init__(self) -> None: ...
    def has_errors(self) -> bool: ...
    def generate_report(self) -> str: ...
    def report(self, name: str, reason: str, **kwargs: Any) -> None: ...

class ParamValidator:
    def validate(self, params: Mapping[str, Any], shape: Shape) -> Any: ...

class ParamValidationDecorator:
    def __init__(self, param_validator: ParamValidator, serializer: Serializer) -> None: ...
    def serialize_to_request(
        self, parameters: Iterable[Any], operation_model: OperationModel
    ) -> Any: ...
