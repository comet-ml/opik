"""
Type annotations for botocore.exceptions module.

Copyright 2025 Vlad Emelianov
"""

from typing import IO, Any, Iterable, Mapping, TypedDict

import requests
from urllib3.exceptions import ReadTimeoutError as _ReadTimeoutError

class _ClientErrorResponseError(TypedDict, total=False):
    Code: str
    Message: str

class _ResponseMetadataTypeDef(TypedDict):
    RequestId: str
    HostId: str
    HTTPStatusCode: int
    HTTPHeaders: dict[str, Any]
    RetryAttempts: int

class _AttributeMapTypeDef(TypedDict, total=False):
    key: str
    value: Any

class _CancellationReasonTypeDef(TypedDict, total=False):
    Code: str
    Message: str
    Item: _AttributeMapTypeDef

class _ClientErrorResponseTypeDef(TypedDict, total=False):
    Status: str
    StatusReason: str
    Error: _ClientErrorResponseError
    ResponseMetadata: _ResponseMetadataTypeDef
    CancellationReasons: list[_CancellationReasonTypeDef]

class BotoCoreError(Exception):
    fmt: str
    def __init__(self, **kwargs: Any) -> None:
        self.kwargs: Mapping[str, Any]

class _DataNotFoundErrorKwargs(TypedDict):
    data_path: str

class DataNotFoundError(BotoCoreError):
    def __init__(self, *, data_path: str = ..., **kwargs: Any) -> None:
        self.kwargs: _DataNotFoundErrorKwargs

class _UnknownServiceErrorKwargs(_DataNotFoundErrorKwargs):
    service_name: str
    known_service_names: Iterable[str]

class UnknownServiceError(DataNotFoundError):
    def __init__(
        self, *, service_name: str = ..., known_service_names: Iterable[str] = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _UnknownServiceErrorKwargs

class _UnknownRegionErrorKwargs(TypedDict):
    service_name: str
    error_msg: str

class UnknownRegionError(BotoCoreError):
    def __init__(self, *, region_name: str = ..., error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownRegionErrorKwargs

class _ApiVersionNotFoundErrorKwargs(TypedDict):
    service_name: str
    api_version: str

class ApiVersionNotFoundError(BotoCoreError):
    def __init__(self, *, data_path: str = ..., api_version: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ApiVersionNotFoundErrorKwargs

class _HTTPClientErrorKwargs(TypedDict):
    error: Exception

class HTTPClientError(BotoCoreError):
    def __init__(self, request: Any = ..., response: Any = ..., **kwargs: Any) -> None:
        self.kwargs: _HTTPClientErrorKwargs

class _ConnectionErrorKwargs(TypedDict):
    error: Exception

class ConnectionError(BotoCoreError):
    def __init__(self, *, error: Exception = ..., **kwargs: Any) -> None:
        self.kwargs: _ConnectionErrorKwargs

class _InvalidIMDSEndpointErrorKwargs(TypedDict):
    endpoint: str

class InvalidIMDSEndpointError(BotoCoreError):
    def __init__(self, *, endpoint: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidIMDSEndpointErrorKwargs

class _InvalidIMDSEndpointModeErrorKwargs(TypedDict):
    mode: str
    valid_modes: Iterable[str]

class InvalidIMDSEndpointModeError(BotoCoreError):
    def __init__(self, *, mode: str = ..., valid_modes: Iterable[str], **kwargs: Any) -> None:
        self.kwargs: _InvalidIMDSEndpointModeErrorKwargs

class _EndpointURLErrorKwargs(_ConnectionErrorKwargs):
    endpoint_url: str

class EndpointConnectionError(ConnectionError):
    def __init__(self, *, endpoint_url: str = ..., **kwargs: Any) -> None:
        self.kwargs: _EndpointURLErrorKwargs

class _SSLErrorKwargs(TypedDict):
    endpoint_url: str
    error: Exception

class SSLError(ConnectionError, requests.exceptions.SSLError):
    def __init__(self, *, endpoint_url: str = ..., error: Exception = ..., **kwargs: Any) -> None:
        self.kwargs: _SSLErrorKwargs

class ConnectionClosedError(HTTPClientError):
    def __init__(
        self, request: Any = ..., response: Any = ..., *, endpoint_url: str = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _EndpointURLErrorKwargs

class ReadTimeoutError(HTTPClientError, requests.exceptions.ReadTimeout, _ReadTimeoutError):
    def __init__(
        self, request: Any = ..., response: Any = ..., *, endpoint_url: str = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _EndpointURLErrorKwargs
        self.request: Any
        self.response: Any

class ConnectTimeoutError(ConnectionError, requests.exceptions.ConnectTimeout):
    def __init__(self, *, endpoint_url: str = ..., **kwargs: Any) -> None:
        self.kwargs: _EndpointURLErrorKwargs

class _ProxyConnectionErrorKwargs(_ConnectionErrorKwargs):
    proxy_url: str

class ProxyConnectionError(ConnectionError, requests.exceptions.ProxyError):
    def __init__(self, *, proxy_url: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ProxyConnectionErrorKwargs

class _ResponseStreamingErrorKwargs(TypedDict):
    error: Any

class ResponseStreamingError(HTTPClientError):
    def __init__(
        self, request: Any = ..., response: Any = ..., *, error: Any = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _ResponseStreamingErrorKwargs

class NoCredentialsError(BotoCoreError): ...
class NoAuthTokenError(BotoCoreError): ...

class _TokenRetrievalErrorKwargs(TypedDict):
    provider: str
    error_msg: str

class TokenRetrievalError(BotoCoreError):
    def __init__(self, *, provider: str = ..., error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _TokenRetrievalErrorKwargs

class _PartialCredentialsErrorKwargs(TypedDict):
    provider: str
    cred_var: str

class PartialCredentialsError(BotoCoreError):
    def __init__(self, *, provider: str = ..., cred_var: str = ..., **kwargs: Any) -> None:
        self.kwargs: _PartialCredentialsErrorKwargs

class _CredentialRetrievalErrorKwargs(TypedDict):
    provider: str

class CredentialRetrievalError(BotoCoreError):
    def __init__(self, *, provider: str = ..., **kwargs: Any) -> None:
        self.kwargs: _CredentialRetrievalErrorKwargs

class _UnknownSignatureVersionErrorKwargs(TypedDict):
    signature_version: str

class UnknownSignatureVersionError(BotoCoreError):
    def __init__(self, *, signature_version: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownSignatureVersionErrorKwargs

class _ServiceNotInRegionErrorKwargs(TypedDict):
    service_name: str
    region_name: str

class ServiceNotInRegionError(BotoCoreError):
    def __init__(self, *, service_name: str = ..., region_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ServiceNotInRegionErrorKwargs

class BaseEndpointResolverError(BotoCoreError): ...
class NoRegionError(BaseEndpointResolverError): ...

class _EndpointVariantErrorKwargs(TypedDict):
    tags: Iterable[str]

class EndpointVariantError(BaseEndpointResolverError):
    def __init__(self, *, tags: Iterable[str] = ..., **kwargs: Any) -> None:
        self.kwargs: _EndpointVariantErrorKwargs

class _UnknownEndpointErrorKwargs(TypedDict):
    service_name: str
    region_name: str

class UnknownEndpointError(BaseEndpointResolverError, ValueError):
    def __init__(self, *, service_name: str = ..., region_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownEndpointErrorKwargs

class _UnknownFIPSEndpointErrorKwargs(TypedDict):
    service_name: str
    region_name: str

class UnknownFIPSEndpointError(BaseEndpointResolverError):
    def __init__(self, *, service_name: str = ..., region_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownFIPSEndpointErrorKwargs

class _ProfileNotFoundKwargs(TypedDict):
    profile: str

class ProfileNotFound(BotoCoreError):
    def __init__(self, *, profile: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ProfileNotFoundKwargs

class _ConfigParseErrorKwargs(TypedDict):
    path: str

class ConfigParseError(BotoCoreError):
    def __init__(self, *, path: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ConfigParseErrorKwargs

class _ConfigNotFoundKwargs(TypedDict):
    path: str

class ConfigNotFound(BotoCoreError):
    def __init__(self, *, path: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ConfigNotFoundKwargs

class _MissingParametersErrorKwargs(TypedDict):
    object: Any
    missing: Iterable[str]

class MissingParametersError(BotoCoreError):
    def __init__(self, *, object: Any = ..., missing: Iterable[str] = ..., **kwargs: Any) -> None:
        self.kwargs: _MissingParametersErrorKwargs

class _ValidationErrorKwargs(TypedDict):
    value: Any
    param: str
    type_name: str

class ValidationError(BotoCoreError):
    def __init__(
        self, *, value: Any = ..., param: str = ..., type_name: str = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _ValidationErrorKwargs

class _ParamValidationErrorKwargs(TypedDict):
    report: str

class ParamValidationError(BotoCoreError):
    def __init__(self, *, report: str = ..., **kwargs: Any) -> None:
        self.kwargs: _ParamValidationErrorKwargs

class _UnknownKeyErrorKwargs(_ValidationErrorKwargs):
    choices: Iterable[Any]

class UnknownKeyError(ValidationError):
    def __init__(
        self, *, value: Any = ..., param: str = ..., choices: Iterable[Any] = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _UnknownKeyErrorKwargs

class _RangeErrorKwargs(_ValidationErrorKwargs):
    min_value: Any
    max_value: Any

class RangeError(ValidationError):
    def __init__(
        self,
        *,
        value: Any = ...,
        param: str = ...,
        min_value: Any = ...,
        max_value: Any = ...,
        **kwargs: Any,
    ) -> None:
        self.kwargs: _RangeErrorKwargs

class _UnknownParameterErrorKwargs(_ValidationErrorKwargs):
    name: str
    operation: str
    choices: Iterable[str]

class UnknownParameterError(ValidationError):
    def __init__(
        self, *, name: str = ..., operation: str = ..., choices: Iterable[str] = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _UnknownParameterErrorKwargs

class _InvalidRegionErrorKwargs(_ValidationErrorKwargs):
    region_name: str

class InvalidRegionError(ValidationError, ValueError):
    def __init__(self, *, region_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidRegionErrorKwargs

class _AliasConflictParameterErrorKwargs(_ValidationErrorKwargs):
    original: str
    alias: str
    operation: str

class AliasConflictParameterError(ValidationError):
    def __init__(
        self, *, original: str = ..., alias: str = ..., operation: str = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _AliasConflictParameterErrorKwargs

class _UnknownServiceStyleKwargs(TypedDict):
    service_style: str

class UnknownServiceStyle(BotoCoreError):
    def __init__(self, *, service_style: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownServiceStyleKwargs

class _PaginationErrorKwargs(TypedDict):
    message: str

class PaginationError(BotoCoreError):
    def __init__(self, *, message: str = ..., **kwargs: Any) -> None:
        self.kwargs: _PaginationErrorKwargs

class _OperationNotPageableErrorKwargs(TypedDict):
    operation_name: str

class OperationNotPageableError(BotoCoreError):
    def __init__(self, *, operation_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _OperationNotPageableErrorKwargs

class _ChecksumErrorKwargs(TypedDict):
    checksum_type: str
    expected_checksum: str
    actual_checksum: str

class ChecksumError(BotoCoreError):
    def __init__(
        self,
        *,
        checksum_type: str = ...,
        expected_checksum: str = ...,
        actual_checksum: str = ...,
        **kwargs: Any,
    ) -> None:
        self.kwargs: _ChecksumErrorKwargs

class _UnseekableStreamErrorKwargs(TypedDict):
    stream_object: IO[Any]

class UnseekableStreamError(BotoCoreError):
    def __init__(self, *, stream_object: IO[Any] = ..., **kwargs: Any) -> None:
        self.kwargs: _UnseekableStreamErrorKwargs

class WaiterError(BotoCoreError):
    def __init__(self, name: str, reason: str, last_response: _ClientErrorResponseTypeDef) -> None:
        self.last_response: _ClientErrorResponseTypeDef

class _IncompleteReadErrorKwargs(TypedDict):
    actual_bytes: int
    expected_bytes: int

class IncompleteReadError(BotoCoreError):
    def __init__(
        self, *, actual_bytes: int = ..., expected_bytes: int = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _IncompleteReadErrorKwargs

class _InvalidExpressionErrorKwargs(TypedDict):
    expression: str

class InvalidExpressionError(BotoCoreError):
    def __init__(self, *, expression: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidExpressionErrorKwargs

class _UnknownCredentialErrorKwargs(TypedDict):
    name: str

class UnknownCredentialError(BotoCoreError):
    def __init__(self, *, name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownCredentialErrorKwargs

class _WaiterConfigErrorKwargs(TypedDict):
    error_msg: str

class WaiterConfigError(BotoCoreError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _WaiterConfigErrorKwargs

class _UnknownClientMethodErrorKwargs(TypedDict):
    method_name: str

class UnknownClientMethodError(BotoCoreError):
    def __init__(self, *, method_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnknownClientMethodErrorKwargs

class _UnsupportedSignatureVersionErrorKwargs(TypedDict):
    signature_version: str

class UnsupportedSignatureVersionError(BotoCoreError):
    def __init__(self, *, signature_version: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedSignatureVersionErrorKwargs

class ClientError(Exception):
    MSG_TEMPLATE: str
    def __init__(self, error_response: _ClientErrorResponseTypeDef, operation_name: str) -> None:
        self.response: _ClientErrorResponseTypeDef
        self.operation_name: str

class EventStreamError(ClientError): ...
class UnsupportedTLSVersionWarning(Warning): ...
class ImminentRemovalWarning(Warning): ...

class _InvalidDNSNameErrorKwargs(TypedDict):
    bucket_name: str

class InvalidDNSNameError(BotoCoreError):
    def __init__(self, *, bucket_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidDNSNameErrorKwargs

class _InvalidS3AddressingStyleErrorKwargs(TypedDict):
    s3_addressing_style: str

class InvalidS3AddressingStyleError(BotoCoreError):
    def __init__(self, *, s3_addressing_style: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidS3AddressingStyleErrorKwargs

class _UnsupportedS3ArnErrorKwargs(TypedDict):
    arn: str

class UnsupportedS3ArnError(BotoCoreError):
    def __init__(self, *, arn: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedS3ArnErrorKwargs

class _UnsupportedS3ControlArnErrorKwargs(TypedDict):
    arn: str
    msg: str

class UnsupportedS3ControlArnError(BotoCoreError):
    def __init__(self, *, arn: str = ..., msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedS3ControlArnErrorKwargs

class _InvalidHostLabelErrorKwargs(TypedDict):
    label: str

class InvalidHostLabelError(BotoCoreError):
    def __init__(self, *, label: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidHostLabelErrorKwargs

class _UnsupportedOutpostResourceErrorKwargs(TypedDict):
    resource_name: str

class UnsupportedOutpostResourceError(BotoCoreError):
    def __init__(self, *, resource_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedOutpostResourceErrorKwargs

class _UnsupportedS3ErrorKwargs(TypedDict):
    msg: str

class UnsupportedS3ConfigurationError(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedS3ErrorKwargs

class UnsupportedS3AccesspointConfigurationError(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedS3ErrorKwargs

class _InvalidEndpointDiscoveryConfigurationErrorKwargs(TypedDict):
    config_value: str

class InvalidEndpointDiscoveryConfigurationError(BotoCoreError):
    def __init__(self, *, config_value: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidEndpointDiscoveryConfigurationErrorKwargs

class UnsupportedS3ControlConfigurationError(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _UnsupportedS3ErrorKwargs

class _InvalidRetryConfigurationErrorKwargs(TypedDict):
    retry_config_option: str

class InvalidRetryConfigurationError(BotoCoreError):
    def __init__(self, *, retry_config_option: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidRetryConfigurationErrorKwargs

class _InvalidMaxRetryAttemptsErrorKwargs(_InvalidRetryConfigurationErrorKwargs):
    provided_max_attempts: int
    min_value: int

class InvalidMaxRetryAttemptsError(InvalidRetryConfigurationError):
    def __init__(
        self, *, provided_max_attempts: int = ..., min_value: int = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _InvalidMaxRetryAttemptsErrorKwargs

class _InvalidRetryModeErrorKwargs(_InvalidMaxRetryAttemptsErrorKwargs):
    provided_retry_mode: str

class InvalidRetryModeError(InvalidRetryConfigurationError):
    def __init__(self, *, provided_retry_mode: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidRetryModeErrorKwargs

class _InvalidS3UsEast1RegionalEndpointConfigErrorKwargs(TypedDict):
    s3_us_east_1_regional_endpoint_config: str

class InvalidS3UsEast1RegionalEndpointConfigError(BotoCoreError):
    def __init__(self, *, s3_us_east_1_regional_endpoint_config: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidS3UsEast1RegionalEndpointConfigErrorKwargs

class _InvalidSTSRegionalEndpointsConfigErrorKwargs(TypedDict):
    sts_regional_endpoints_config: str

class InvalidSTSRegionalEndpointsConfigError(BotoCoreError):
    def __init__(self, *, sts_regional_endpoints_config: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidSTSRegionalEndpointsConfigErrorKwargs

class _StubResponseErrorKwargs(TypedDict):
    operation_name: str

class StubResponseError(BotoCoreError):
    def __init__(self, *, operation_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _StubResponseErrorKwargs

class StubAssertionError(StubResponseError, AssertionError): ...
class UnStubbedResponseError(StubResponseError): ...

class _InvalidConfigErrorKwargs(TypedDict):
    error_msg: str

class InvalidConfigError(BotoCoreError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidConfigErrorKwargs

class _InfiniteLoopConfigErrorKwargs(_InvalidConfigErrorKwargs):
    source_profile: str
    visited_profiles: Iterable[str]

class InfiniteLoopConfigError(InvalidConfigError):
    def __init__(
        self, *, source_profile: str = ..., visited_profiles: Iterable[str] = ..., **kwargs: Any
    ) -> None:
        self.kwargs: _InfiniteLoopConfigErrorKwargs

class RefreshWithMFAUnsupportedError(BotoCoreError): ...
class MD5UnavailableError(BotoCoreError): ...

class _MissingDependencyExceptionKwargs(TypedDict):
    msg: str

class MissingDependencyException(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _MissingDependencyExceptionKwargs

class _MetadataRetrievalErrorKwargs(TypedDict):
    error_msg: str

class MetadataRetrievalError(BotoCoreError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _MetadataRetrievalErrorKwargs

class UndefinedModelAttributeError(Exception): ...

class _MissingServiceIdErrorKwargs(TypedDict):
    service_name: str

class MissingServiceIdError(UndefinedModelAttributeError):
    fmt: str
    def __init__(self, *, service_name: str = ..., **kwargs: Any) -> None:
        self.kwargs: _MissingServiceIdErrorKwargs

class SSOError(BotoCoreError): ...

class _SSOTokenLoadErrorKwargs(TypedDict):
    error_msg: str

class SSOTokenLoadError(SSOError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _SSOTokenLoadErrorKwargs

class UnauthorizedSSOTokenError(SSOError): ...
class CapacityNotAvailableError(BotoCoreError): ...
class InvalidProxiesConfigError(BotoCoreError): ...

class _InvalidDefaultsModeKwargs(TypedDict):
    mode: str
    valid_modes: Iterable[str]

class InvalidDefaultsMode(BotoCoreError):
    def __init__(self, *, mode: str = ..., valid_modes: Iterable[str] = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidDefaultsModeKwargs

class _AwsChunkedWrapperErrorKwargs(TypedDict):
    error_msg: str

class AwsChunkedWrapperError(BotoCoreError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _AwsChunkedWrapperErrorKwargs

class _FlexibleChecksumErrorKwargs(TypedDict):
    error_msg: str

class FlexibleChecksumError(BotoCoreError):
    def __init__(self, *, error_msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _FlexibleChecksumErrorKwargs

class _InvalidEndpointConfigurationErrorKwargs(TypedDict):
    msg: str

class InvalidEndpointConfigurationError(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _InvalidEndpointConfigurationErrorKwargs

class _EndpointProviderErrorKwargs(TypedDict):
    msg: str

class EndpointProviderError(BotoCoreError):
    def __init__(self, *, msg: str = ..., **kwargs: Any) -> None:
        self.kwargs: _EndpointProviderErrorKwargs

class EndpointResolutionError(EndpointProviderError): ...
class UnknownEndpointResolutionBuiltInName(EndpointProviderError): ...
class InvalidChecksumConfigError(BotoCoreError): ...
class UnsupportedServiceProtocolsError(BotoCoreError): ...
