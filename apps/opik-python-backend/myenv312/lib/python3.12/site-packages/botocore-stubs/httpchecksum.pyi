"""
Type annotations for botocore.httpchecksum module.

Copyright 2025 Vlad Emelianov
"""

import logging
from typing import IO, Any, Iterator, Mapping, Sequence

from botocore.awsrequest import AWSHTTPResponse
from botocore.compat import HAS_CRT as HAS_CRT
from botocore.exceptions import AwsChunkedWrapperError as AwsChunkedWrapperError
from botocore.exceptions import FlexibleChecksumError as FlexibleChecksumError
from botocore.model import OperationModel
from botocore.response import StreamingBody as StreamingBody
from botocore.utils import determine_content_length as determine_content_length

logger: logging.Logger = ...
DEFAULT_CHECKSUM_ALGORITHM: str = ...

class BaseChecksum:
    def update(self, chunk: bytes | bytearray) -> None: ...
    def digest(self) -> bytes: ...
    def b64digest(self) -> str: ...
    def handle(self, body: bytes | bytearray | IO[Any]) -> str: ...

class Crc32Checksum(BaseChecksum):
    def __init__(self) -> None: ...

class CrtCrc32Checksum(BaseChecksum):
    def __init__(self) -> None: ...

class CrtCrc32cChecksum(BaseChecksum):
    def __init__(self) -> None: ...

class CrtCrc64NvmeChecksum(BaseChecksum):
    def __init__(self) -> None: ...

class Sha1Checksum(BaseChecksum):
    def __init__(self) -> None: ...

class Sha256Checksum(BaseChecksum):
    def __init__(self) -> None: ...

class AwsChunkedWrapper:
    def __init__(
        self,
        raw: IO[Any],
        checksum_cls: type[BaseChecksum] | None = ...,
        checksum_name: str = ...,
        chunk_size: int | None = ...,
    ) -> None: ...
    def seek(self, offset: int, whence: int = ...) -> None: ...
    def read(self, size: int | None = ...) -> bytes: ...
    def __iter__(self) -> Iterator[bytes]: ...

class StreamingChecksumBody(StreamingBody):
    def __init__(
        self, raw_stream: IO[Any], content_length: int, checksum: BaseChecksum, expected: str
    ) -> None: ...
    def read(self, amt: int | None = ...) -> bytes: ...

def resolve_checksum_context(
    request: Mapping[str, Any], operation_model: OperationModel, params: Mapping[str, Any]
) -> None: ...
def resolve_request_checksum_algorithm(
    request: Mapping[str, Any],
    operation_model: OperationModel,
    params: Mapping[str, Any],
    supported_algorithms: Sequence[str] | None = ...,
) -> None: ...
def apply_request_checksum(request: Mapping[str, Any]) -> None: ...
def resolve_response_checksum_algorithms(
    request: Mapping[str, Any],
    operation_model: OperationModel,
    params: Mapping[str, Any],
    supported_algorithms: Sequence[str] | None = ...,
) -> None: ...
def handle_checksum_body(
    http_response: AWSHTTPResponse,
    response: Mapping[str, Any],
    context: Mapping[str, Any],
    operation_model: OperationModel,
) -> None: ...
