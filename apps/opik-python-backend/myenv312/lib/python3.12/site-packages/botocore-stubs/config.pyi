"""
Type annotations for botocore.config module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Literal, Mapping, TypedDict, TypeVar

from botocore.compat import OrderedDict as OrderedDict
from botocore.endpoint import DEFAULT_TIMEOUT as DEFAULT_TIMEOUT
from botocore.endpoint import MAX_POOL_CONNECTIONS as MAX_POOL_CONNECTIONS
from botocore.exceptions import InvalidMaxRetryAttemptsError as InvalidMaxRetryAttemptsError
from botocore.exceptions import InvalidRetryConfigurationError as InvalidRetryConfigurationError
from botocore.exceptions import InvalidRetryModeError as InvalidRetryModeError
from botocore.exceptions import InvalidS3AddressingStyleError as InvalidS3AddressingStyleError

class _RetryDict(TypedDict, total=False):
    total_max_attempts: int
    max_attempts: int
    mode: Literal["legacy", "standard", "adaptive"]

class _S3Dict(TypedDict, total=False):
    use_accelerate_endpoint: bool
    payload_signing_enabled: bool
    addressing_style: Literal["auto", "virtual", "path"]
    us_east_1_regional_endpoint: Literal["regional", "legacy"]

class _ProxiesConfigDict(TypedDict, total=False):
    proxy_ca_bundle: str
    proxy_client_cert: str | tuple[str, str]
    proxy_use_forwarding_for_https: bool

_Config = TypeVar("_Config", bound=Config)

class Config:
    OPTION_DEFAULTS: OrderedDict[str, None]
    NON_LEGACY_OPTION_DEFAULTS: dict[str, None]
    def __init__(
        self,
        region_name: str | None = None,
        signature_version: str | None = None,
        user_agent: str | None = None,
        user_agent_extra: str | None = None,
        connect_timeout: float | None = 60,
        read_timeout: float | None = 60,
        parameter_validation: bool | None = True,
        max_pool_connections: int | None = 10,
        proxies: Mapping[str, str] | None = None,
        proxies_config: _ProxiesConfigDict | None = None,
        s3: _S3Dict | None = None,
        retries: _RetryDict | None = None,
        client_cert: str | tuple[str, str] | None = None,
        inject_host_prefix: bool | None = True,
        endpoint_discovery_enabled: bool | None = None,
        use_dualstack_endpoint: bool | None = None,
        use_fips_endpoint: bool | None = None,
        defaults_mode: bool | None = None,
        tcp_keepalive: bool | None = False,
        request_min_compression_size_bytes: int | None = None,
        disable_request_compression: bool | None = None,
        sigv4a_signing_region_set: str | None = None,
        client_context_params: Mapping[str, Any] | None = None,
        request_checksum_calculation: Literal["when_supported", "when_required"] | None = None,
        response_checksum_validation: Literal["when_supported", "when_required"] | None = None,
        account_id_endpoint_mode: Literal["preferred", "disabled", "required"] | None = None,
    ) -> None: ...
    def merge(self: _Config, other_config: _Config) -> _Config: ...
    @property
    def inject_host_prefix(self) -> bool: ...
    @inject_host_prefix.setter
    def inject_host_prefix(self, value: bool) -> None: ...
