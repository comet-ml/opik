"""
Type annotations for botocore.regions module.

Copyright 2025 Vlad Emelianov
"""

from collections.abc import Iterable
from enum import Enum
from logging import Logger
from typing import Any, Mapping

from botocore.auth import AUTH_TYPE_MAPS as AUTH_TYPE_MAPS
from botocore.compat import HAS_CRT as HAS_CRT
from botocore.crt import CRT_SUPPORTED_AUTH_TYPES as CRT_SUPPORTED_AUTH_TYPES
from botocore.endpoint_provider import RuleSetEndpoint
from botocore.exceptions import BotoCoreError
from botocore.exceptions import NoRegionError as NoRegionError
from botocore.hooks import BaseEventHooks
from botocore.model import OperationModel, ServiceModel

LOG: Logger = ...
DEFAULT_URI_TEMPLATE: str
DEFAULT_SERVICE_DATA: dict[str, dict[str, Any]]

class BaseEndpointResolver:
    def construct_endpoint(self, service_name: str, region_name: str | None = ...) -> None: ...
    def get_available_partitions(self) -> list[str]: ...
    def get_available_endpoints(
        self,
        service_name: str,
        partition_name: str = ...,
        allow_non_regional: bool = ...,
    ) -> list[str]: ...

class EndpointResolver(BaseEndpointResolver):
    def __init__(self, endpoint_data: Mapping[str, Any], uses_builtin_data: bool = ...) -> None: ...
    def get_service_endpoints_data(self, service_name: str, partition_name: str = ...) -> Any: ...
    def get_available_partitions(self) -> list[str]: ...
    def get_available_endpoints(
        self,
        service_name: str,
        partition_name: str = ...,
        allow_non_regional: bool = ...,
        endpoint_variant_tags: Iterable[str] | None = ...,
    ) -> list[str]: ...
    def get_partition_dns_suffix(
        self, partition_name: str, endpoint_variant_tags: Iterable[str] | None = ...
    ) -> str: ...
    def construct_endpoint(  # type: ignore [override]
        self,
        service_name: str,
        region_name: str | None = ...,
        partition_name: str | None = ...,
        use_dualstack_endpoint: bool = ...,
        use_fips_endpoint: bool = ...,
    ) -> dict[str, Any] | None: ...
    def get_partition_for_region(self, region_name: str) -> str: ...

class EndpointResolverBuiltins(Enum):
    AWS_REGION = "AWS::Region"
    AWS_USE_FIPS = "AWS::UseFIPS"
    AWS_USE_DUALSTACK = "AWS::UseDualStack"
    AWS_STS_USE_GLOBAL_ENDPOINT = "AWS::STS::UseGlobalEndpoint"
    AWS_S3_USE_GLOBAL_ENDPOINT = "AWS::S3::UseGlobalEndpoint"
    AWS_S3_ACCELERATE = "AWS::S3::Accelerate"
    AWS_S3_FORCE_PATH_STYLE = "AWS::S3::ForcePathStyle"
    AWS_S3_USE_ARN_REGION = "AWS::S3::UseArnRegion"
    AWS_S3CONTROL_USE_ARN_REGION = "AWS::S3Control::UseArnRegion"
    AWS_S3_DISABLE_MRAP = "AWS::S3::DisableMultiRegionAccessPoints"
    SDK_ENDPOINT = "SDK::Endpoint"
    ACCOUNT_ID = "AWS::Auth::AccountId"
    ACCOUNT_ID_ENDPOINT_MODE = "AWS::Auth::AccountIdEndpointMode"

class EndpointRulesetResolver:
    def __init__(
        self,
        endpoint_ruleset_data: Mapping[str, Any],
        partition_data: Mapping[str, Any],
        service_model: ServiceModel,
        builtins: EndpointResolverBuiltins,
        client_context: Mapping[str, Any],
        event_emitter: BaseEventHooks,
        use_ssl: bool = ...,
        requested_auth_scheme: str | None = ...,
    ) -> None: ...
    def construct_endpoint(
        self,
        operation_model: OperationModel,
        call_args: Mapping[str, Any] | None,
        request_context: Mapping[str, Any],
    ) -> RuleSetEndpoint: ...
    def auth_schemes_to_signing_ctx(
        self, auth_schemes: list[Mapping[str, Any]]
    ) -> tuple[str, dict[str, Any]]: ...
    def ruleset_error_to_botocore_exception(
        self, ruleset_exception: Exception, params: Mapping[str, Any]
    ) -> BotoCoreError: ...
