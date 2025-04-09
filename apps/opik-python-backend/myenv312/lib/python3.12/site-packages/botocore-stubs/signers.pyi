"""
Type annotations for botocore.signers module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, Mapping

from botocore.awsrequest import create_request_object as create_request_object
from botocore.awsrequest import prepare_request_dict as prepare_request_dict
from botocore.compat import OrderedDict as OrderedDict
from botocore.credentials import Credentials, ReadOnlyCredentials
from botocore.exceptions import UnknownClientMethodError as UnknownClientMethodError
from botocore.exceptions import UnknownSignatureVersionError as UnknownSignatureVersionError
from botocore.exceptions import UnsupportedSignatureVersionError as UnsupportedSignatureVersionError
from botocore.hooks import BaseEventHooks
from botocore.model import ServiceId
from botocore.utils import datetime2timestamp as datetime2timestamp

class RequestSigner:
    def __init__(
        self,
        service_id: ServiceId,
        region_name: str,
        signing_name: str,
        signature_version: str,
        credentials: Credentials | ReadOnlyCredentials,
        event_emitter: BaseEventHooks,
        auth_token: str | None = ...,
    ) -> None: ...
    @property
    def region_name(self) -> str: ...
    @property
    def signature_version(self) -> str: ...
    @property
    def signing_name(self) -> str: ...
    def handler(
        self, operation_name: str | None = ..., request: Any | None = ..., **kwargs: Any
    ) -> Any: ...
    def sign(
        self,
        operation_name: str,
        request: Any,
        region_name: str | None = ...,
        signing_type: str = ...,
        expires_in: Any | None = ...,
        signing_name: str | None = ...,
    ) -> None: ...
    def get_auth_instance(
        self,
        signing_name: str,
        region_name: str,
        signature_version: str | None = ...,
        request_credentials: Credentials | ReadOnlyCredentials | None = ...,
        **kwargs: Any,
    ) -> Any: ...
    get_auth: Any = ...
    def generate_presigned_url(
        self,
        request_dict: Mapping[str, Any],
        operation_name: str,
        expires_in: int = ...,
        region_name: str | None = ...,
        signing_name: str | None = ...,
    ) -> Any: ...

class CloudFrontSigner:
    key_id: Any = ...
    rsa_signer: Any = ...
    def __init__(self, key_id: str, rsa_signer: Any) -> None: ...
    def generate_presigned_url(
        self, url: str, date_less_than: Any | None = ..., policy: Any | None = ...
    ) -> str: ...
    def build_policy(
        self,
        resource: Any,
        date_less_than: Any,
        date_greater_than: Any | None = ...,
        ip_address: Any | None = ...,
    ) -> str: ...

def add_generate_db_auth_token(class_attributes: Any, **kwargs: Any) -> None: ...
def add_dsql_generate_db_auth_token_methods(
    class_attributes: dict[str, Any], **kwargs: Any
) -> None: ...
def generate_db_auth_token(
    self: Any, DBHostname: Any, Port: Any, DBUsername: Any, Region: Any | None = ...
) -> Any: ...
def dsql_generate_db_connect_auth_token(
    self: Any, Hostname: str, Region: str | None = ..., ExpiresIn: int = ...
) -> str: ...
def dsql_generate_db_connect_admin_auth_token(
    self: Any, Hostname: str, Region: str | None = ..., ExpiresIn: int = ...
) -> str: ...

class S3PostPresigner:
    def __init__(self, request_signer: Any) -> None: ...
    def generate_presigned_post(
        self,
        request_dict: Mapping[str, Any],
        fields: Any | None = ...,
        conditions: Any | None = ...,
        expires_in: int = ...,
        region_name: str | None = ...,
    ) -> Any: ...

def add_generate_presigned_url(class_attributes: Any, **kwargs: Any) -> None: ...
def generate_presigned_url(
    self: Any,
    ClientMethod: Any,
    Params: Any | None = ...,
    ExpiresIn: int = ...,
    HttpMethod: Any | None = ...,
) -> Any: ...
def add_generate_presigned_post(class_attributes: Any, **kwargs: Any) -> None: ...
def generate_presigned_post(
    self: Any,
    Bucket: Any,
    Key: Any,
    Fields: Any | None = ...,
    Conditions: Any | None = ...,
    ExpiresIn: int = ...,
) -> Any: ...
