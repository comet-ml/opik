"""
Type annotations for botocore.stub module.

Copyright 2025 Vlad Emelianov
"""

from types import TracebackType
from typing import Any, Literal, Mapping, TypeVar

from botocore.awsrequest import AWSResponse as AWSResponse
from botocore.client import BaseClient
from botocore.exceptions import ParamValidationError as ParamValidationError
from botocore.exceptions import StubAssertionError as StubAssertionError
from botocore.exceptions import StubResponseError as StubResponseError
from botocore.exceptions import UnStubbedResponseError as UnStubbedResponseError
from botocore.validate import validate_parameters as validate_parameters

class _ANY:
    def __eq__(self, other: object) -> Literal[True]: ...
    def __ne__(self, other: object) -> Literal[False]: ...

ANY: _ANY

_R = TypeVar("_R")

class Stubber:
    def __init__(self, client: BaseClient) -> None:
        self.client: BaseClient = ...

    def __enter__(self: _R) -> _R: ...
    def __exit__(
        self,
        exception_type: type[BaseException] | None,
        exception_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None: ...
    def activate(self) -> None: ...
    def deactivate(self) -> None: ...
    def add_response(
        self,
        method: str,
        service_response: Mapping[str, Any],
        expected_params: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def add_client_error(
        self,
        method: str,
        service_error_code: str = ...,
        service_message: str = ...,
        http_status_code: int = ...,
        service_error_meta: Mapping[str, Any] | None = ...,
        expected_params: Mapping[str, Any] | None = ...,
        response_meta: Mapping[str, Any] | None = ...,
        modeled_fields: Mapping[str, Any] | None = ...,
    ) -> None: ...
    def assert_no_pending_responses(self) -> None: ...
