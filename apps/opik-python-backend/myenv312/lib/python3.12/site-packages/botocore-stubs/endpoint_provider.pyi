"""
Type annotations for botocore.endpoint_provider module.

Copyright 2025 Vlad Emelianov
"""

import logging
from enum import Enum
from string import Formatter
from typing import (
    Any,
    Callable,
    Iterable,
    Mapping,
    NamedTuple,
    Pattern,
)

from botocore.compat import quote as quote
from botocore.compat import urlparse as urlparse
from botocore.exceptions import EndpointResolutionError as EndpointResolutionError
from botocore.utils import ArnParser as ArnParser
from botocore.utils import InvalidArnException as InvalidArnException
from botocore.utils import is_valid_ipv4_endpoint_url as is_valid_ipv4_endpoint_url
from botocore.utils import is_valid_ipv6_endpoint_url as is_valid_ipv6_endpoint_url
from botocore.utils import normalize_url_path as normalize_url_path
from botocore.utils import percent_encode as percent_encode

logger: logging.Logger = ...
TEMPLATE_STRING_RE: Pattern[str] = ...
GET_ATTR_RE: Pattern[str] = ...
VALID_HOST_LABEL_RE: Pattern[str] = ...
CACHE_SIZE: int = ...
ARN_PARSER: ArnParser = ...
STRING_FORMATTER: Formatter = ...

class RuleSetStandardLibrary:
    def __init__(self, partitions_data: Mapping[str, Any]) -> None:
        self.partitions_data: dict[str, Any]

    def is_func(self, argument: Any) -> bool: ...
    def is_ref(self, argument: Any) -> bool: ...
    def is_template(self, argument: Any) -> bool: ...
    def resolve_template_string(self, value: str, scope_vars: Mapping[str, Any]) -> str: ...
    def resolve_value(self, value: str, scope_vars: Mapping[str, Any]) -> Any: ...
    def convert_func_name(self, value: str) -> str: ...
    def call_function(
        self, func_signature: Mapping[str, Any], scope_vars: Mapping[str, Any]
    ) -> Any: ...
    def is_set(self, value: Any) -> bool: ...
    def get_attr(self, value: Mapping[str, Any], path: str) -> Any: ...
    def format_partition_output(self, partition: Mapping[str, Any]) -> dict[str, Any]: ...
    def is_partition_match(self, region: str, partition: Mapping[str, Any]) -> bool: ...
    def aws_partition(self, value: str) -> dict[str, Any]: ...
    def aws_parse_arn(self, value: str) -> dict[str, Any]: ...
    def is_valid_host_label(self, value: str, allow_subdomains: bool) -> bool: ...
    def string_equals(self, value1: str, value2: str) -> bool: ...
    def uri_encode(self, value: str) -> str: ...
    def parse_url(self, value: str) -> dict[str, Any]: ...
    def boolean_equals(self, value1: bool, value2: bool) -> bool: ...
    def is_ascii(self, value: str) -> bool: ...
    def substring(self, value: str, start: int, stop: int, reverse: bool) -> str: ...
    def aws_is_virtual_hostable_s3_bucket(self, value: str, allow_subdomains: bool) -> bool: ...

RuleSetStandardLibary = RuleSetStandardLibrary

class BaseRule:
    def __init__(
        self, conditions: Iterable[Callable[..., Any]], documentation: str | None = ...
    ) -> None:
        self.conditions: Iterable[Callable[..., Any]]
        self.documentation: str | None

    def evaluate(self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary) -> Any: ...
    def evaluate_conditions(
        self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary
    ) -> bool: ...

class RuleSetEndpoint(NamedTuple):
    url: str
    properties: dict[str, Any]
    headers: dict[str, Any]

class EndpointRule(BaseRule):
    def __init__(self, endpoint: Mapping[str, Any], **kwargs: Any) -> None:
        self.endpoint: dict[str, Any]

    def evaluate(
        self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary
    ) -> RuleSetEndpoint: ...
    def resolve_properties(
        self,
        properties: Mapping[str, Any] | list[Any] | str,
        scope_vars: Mapping[str, Any],
        rule_lib: RuleSetStandardLibary,
    ) -> dict[str, Any]: ...
    def resolve_headers(
        self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary
    ) -> dict[str, Any]: ...

class ErrorRule(BaseRule):
    def __init__(self, error: Any, **kwargs: Any) -> None:
        self.error: Any

    def evaluate(self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary) -> None: ...

class TreeRule(BaseRule):
    def __init__(self, rules: Iterable[Mapping[str, Any]], **kwargs: Any) -> None:
        self.rules: Iterable[dict[str, Any]]

    def evaluate(
        self, scope_vars: Mapping[str, Any], rule_lib: RuleSetStandardLibary
    ) -> RuleSetEndpoint | None: ...

class RuleCreator:
    endpoint: type[EndpointRule]
    error: type[ErrorRule]
    tree: type[TreeRule]
    @classmethod
    def create(cls, **kwargs: Any) -> BaseRule: ...

class ParameterType(Enum):
    string = type(str)
    boolean = type(bool)
    stringarray = type(tuple)

class ParameterDefinition:
    def __init__(
        self,
        name: str,
        parameter_type: ParameterType,
        documentation: str | None = ...,
        builtIn: bool | None = ...,
        default: bool | None = ...,
        required: bool | None = ...,
        deprecated: bool | None = ...,
    ) -> None:
        self.name: str
        self.parameter_type: ParameterType
        self.documentation: str | None
        self.built_in: bool | None
        self.default: bool | None
        self.required: bool | None
        self.deprecated: bool | None

    def validate_input(self, value: Any) -> None: ...
    def process_input(self, value: Any) -> Any: ...

class RuleSet:
    def __init__(
        self,
        version: str,
        parameters: Mapping[str, Any],
        rules: Iterable[Mapping[str, Any]],
        partitions: Any,
        documentation: str | None = ...,
    ) -> None:
        self.version: str
        self.parameters: dict[str, Any]
        self.rules: list[BaseRule]
        self.rule_lib: RuleSetStandardLibary
        self.documentation: str | None

    def process_input_parameters(self, input_params: Mapping[str, Any]) -> None: ...
    def evaluate(self, input_parameters: Mapping[str, Any]) -> Any: ...

class EndpointProvider:
    def __init__(self, ruleset_data: Mapping[str, Any], partition_data: Mapping[str, Any]) -> None:
        self.ruleset: RuleSet

    def resolve_endpoint(self, **input_parameters: Any) -> RuleSetEndpoint: ...
