"""
Type annotations for botocore.args module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any, Literal, Mapping, TypedDict

from botocore.client import ClientEndpointBridge
from botocore.config import Config as Config
from botocore.configprovider import ConfigValueStore
from botocore.credentials import Credentials
from botocore.endpoint import Endpoint
from botocore.endpoint import EndpointCreator as EndpointCreator
from botocore.errorfactory import ClientExceptionsFactory
from botocore.hooks import BaseEventHooks
from botocore.loaders import Loader
from botocore.model import ServiceModel
from botocore.parsers import ResponseParser, ResponseParserFactory
from botocore.serialize import BaseRestSerializer
from botocore.signers import RequestSigner as RequestSigner
from botocore.useragent import UserAgentString

logger: Logger = ...

VALID_REGIONAL_ENDPOINTS_CONFIG: list[str] = ...
LEGACY_GLOBAL_STS_REGIONS: list[str] = ...
USERAGENT_APPID_MAXLEN: int = ...
VALID_REQUEST_CHECKSUM_CALCULATION_CONFIG: tuple[str, ...] = ...
VALID_RESPONSE_CHECKSUM_VALIDATION_CONFIG: tuple[str, ...] = ...
PRIORITY_ORDERED_SUPPORTED_PROTOCOLS: tuple[str, ...] = ...
VALID_ACCOUNT_ID_ENDPOINT_MODE_CONFIG: tuple[str, ...] = ...

class _GetClientArgsTypeDef(TypedDict):
    serializer: BaseRestSerializer
    endpoint: Endpoint
    response_parser: ResponseParser
    event_emitter: BaseEventHooks
    request_signer: RequestSigner
    service_model: ServiceModel
    loader: Loader
    client_config: Config
    partition: str | None
    exceptions_factory: ClientExceptionsFactory

class ClientArgsCreator:
    def __init__(
        self,
        event_emitter: BaseEventHooks,
        user_agent: str,
        response_parser_factory: ResponseParserFactory,
        loader: Loader,
        exceptions_factory: ClientExceptionsFactory,
        config_store: ConfigValueStore,
        user_agent_creator: UserAgentString | None = ...,
    ) -> None: ...
    def get_client_args(
        self,
        service_model: ServiceModel,
        region_name: str,
        is_secure: bool,
        endpoint_url: str | None,
        verify: str | bool | None,
        credentials: Any | None,
        scoped_config: Mapping[str, Any] | None,
        client_config: Config | None,
        endpoint_bridge: ClientEndpointBridge,
        auth_token: str | None = ...,
        endpoints_ruleset_data: Mapping[str, Any] | None = ...,
        partition_data: Mapping[str, Any] | None = ...,
    ) -> _GetClientArgsTypeDef: ...
    def compute_client_args(
        self,
        service_model: ServiceModel,
        client_config: Config | None,
        endpoint_bridge: ClientEndpointBridge,
        region_name: str,
        endpoint_url: str,
        is_secure: bool,
        scoped_config: Mapping[str, Any] | None,
    ) -> Any: ...
    def compute_s3_config(self, client_config: Config | None) -> dict[str, Any]: ...
    def compute_endpoint_resolver_builtin_defaults(
        self,
        region_name: str,
        service_name: str,
        s3_config: Mapping[str, Any],
        endpoint_bridge: ClientEndpointBridge,
        client_endpoint_url: str,
        legacy_endpoint_url: str,
        credentials: Credentials | None,
        account_id_endpoint_mode: Literal["preferred", "disabled", "required"] | None,
    ) -> dict[str, Any]: ...
