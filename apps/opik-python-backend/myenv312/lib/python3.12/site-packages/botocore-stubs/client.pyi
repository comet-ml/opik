"""
Type annotations for botocore.client module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any, Mapping

from botocore.args import ClientArgsCreator as ClientArgsCreator
from botocore.auth import AUTH_TYPE_MAPS as AUTH_TYPE_MAPS
from botocore.awsrequest import prepare_request_dict as prepare_request_dict
from botocore.config import Config as Config
from botocore.configprovider import ConfigValueStore
from botocore.discovery import EndpointDiscoveryHandler as EndpointDiscoveryHandler
from botocore.discovery import EndpointDiscoveryManager as EndpointDiscoveryManager
from botocore.discovery import (
    block_endpoint_discovery_required_operations as block_endpoint_discovery_required_operations,
)
from botocore.errorfactory import BaseClientExceptions, ClientExceptionsFactory
from botocore.exceptions import ClientError as ClientError
from botocore.exceptions import DataNotFoundError as DataNotFoundError
from botocore.exceptions import (
    InvalidEndpointDiscoveryConfigurationError as InvalidEndpointDiscoveryConfigurationError,
)
from botocore.exceptions import OperationNotPageableError as OperationNotPageableError
from botocore.exceptions import UnknownSignatureVersionError as UnknownSignatureVersionError
from botocore.history import HistoryRecorder
from botocore.history import get_global_history_recorder as get_global_history_recorder
from botocore.hooks import BaseEventHooks
from botocore.hooks import first_non_none_response as first_non_none_response
from botocore.loaders import Loader
from botocore.model import ServiceModel as ServiceModel
from botocore.paginate import Paginator as Paginator
from botocore.parsers import ResponseParser, ResponseParserFactory
from botocore.regions import BaseEndpointResolver, EndpointRulesetResolver
from botocore.retries import adaptive as adaptive
from botocore.retries import standard as standard
from botocore.serialize import Serializer
from botocore.signers import RequestSigner
from botocore.useragent import UserAgentString
from botocore.utils import CachedProperty as CachedProperty
from botocore.utils import S3ArnParamHandler as S3ArnParamHandler
from botocore.utils import S3ControlArnParamHandler as S3ControlArnParamHandler
from botocore.utils import S3ControlEndpointSetter as S3ControlEndpointSetter
from botocore.utils import S3EndpointSetter as S3EndpointSetter
from botocore.utils import S3RegionRedirector as S3RegionRedirector
from botocore.utils import ensure_boolean as ensure_boolean
from botocore.utils import get_service_module_name as get_service_module_name
from botocore.waiter import Waiter

logger: Logger = ...
history_recorder: HistoryRecorder = ...

class ClientCreator:
    def __init__(
        self,
        loader: Loader,
        endpoint_resolver: BaseEndpointResolver,
        user_agent: str,
        event_emitter: BaseEventHooks,
        retry_handler_factory: Any,
        retry_config_translator: Any,
        response_parser_factory: ResponseParserFactory | None = ...,
        exceptions_factory: ClientExceptionsFactory | None = ...,
        config_store: ConfigValueStore | None = ...,
        user_agent_creator: UserAgentString | None = ...,
    ) -> None: ...
    def create_client(
        self,
        service_name: str,
        region_name: str,
        is_secure: bool = ...,
        endpoint_url: str | None = ...,
        verify: str | bool | None = ...,
        credentials: Any | None = ...,
        scoped_config: Mapping[str, Any] | None = ...,
        api_version: str | None = ...,
        client_config: Config | None = ...,
        auth_token: str | None = ...,
    ) -> BaseClient: ...
    def create_client_class(self, service_name: str, api_version: str | None = ...) -> None: ...

class ClientEndpointBridge:
    DEFAULT_ENDPOINT: str = ...
    def __init__(
        self,
        endpoint_resolver: BaseEndpointResolver,
        scoped_config: Mapping[str, Any] | None = ...,
        client_config: Config | None = ...,
        default_endpoint: str | None = ...,
        service_signing_name: str | None = ...,
        config_store: ConfigValueStore | None = ...,
        service_signature_version: str | None = ...,
    ) -> None:
        self.service_signing_name: str
        self.endpoint_resolver: BaseEndpointResolver
        self.scoped_config: Mapping[str, Any]
        self.client_config: Config
        self.default_endpoint: str
        self.config_store: ConfigValueStore

    def resolve(
        self,
        service_name: str,
        region_name: str | None = ...,
        endpoint_url: str | None = ...,
        is_secure: bool = ...,
    ) -> None: ...
    def resolver_uses_builtin_data(self) -> bool: ...

class BaseClient:
    def __init__(
        self,
        serializer: Serializer,
        endpoint: str,
        response_parser: ResponseParser,
        event_emitter: BaseEventHooks,
        request_signer: RequestSigner,
        service_model: ServiceModel,
        loader: Loader,
        client_config: Config,
        partition: str,
        exceptions_factory: ClientExceptionsFactory,
        endpoint_ruleset_resolver: EndpointRulesetResolver | None = ...,
        user_agent_creator: UserAgentString | None = ...,
    ) -> None:
        self.meta: ClientMeta
    # FIXME: it hides `has no attribute` errors on Client type checking
    # def __getattr__(self, item: str) -> Any: ...
    def close(self) -> None: ...
    def get_paginator(self, operation_name: str) -> Paginator[Any]: ...
    def can_paginate(self, operation_name: str) -> bool: ...
    def get_waiter(self, waiter_name: str) -> Waiter: ...
    @CachedProperty
    def waiter_names(self) -> list[str]: ...
    @property
    def exceptions(self) -> BaseClientExceptions: ...

class ClientMeta:
    def __init__(
        self,
        events: BaseEventHooks,
        client_config: Config,
        endpoint_url: str,
        service_model: ServiceModel,
        method_to_api_mapping: Mapping[str, str],
        partition: str,
    ) -> None:
        self.events: BaseEventHooks

    @property
    def service_model(self) -> ServiceModel: ...
    @property
    def region_name(self) -> str: ...
    @property
    def endpoint_url(self) -> str: ...
    @property
    def config(self) -> Config: ...
    @property
    def method_to_api_mapping(self) -> dict[str, str]: ...
    @property
    def partition(self) -> str: ...
