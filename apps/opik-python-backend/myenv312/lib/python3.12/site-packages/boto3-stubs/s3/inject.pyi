"""
Type annotations for boto3.s3.inject module.

Copyright 2024 Vlad Emelianov
"""

from typing import IO, Any, Callable

from boto3 import utils as utils
from boto3.s3.transfer import ProgressCallbackInvoker as ProgressCallbackInvoker
from boto3.s3.transfer import S3Transfer as S3Transfer
from boto3.s3.transfer import TransferConfig as TransferConfig
from boto3.s3.transfer import create_transfer_manager as create_transfer_manager
from botocore.client import BaseClient
from botocore.exceptions import ClientError as ClientError

def inject_s3_transfer_methods(class_attributes: dict[str, Any], **kwargs: Any) -> None: ...
def inject_bucket_methods(class_attributes: dict[str, Any], **kwargs: Any) -> None: ...
def inject_object_methods(class_attributes: dict[str, Any], **kwargs: Any) -> None: ...
def inject_object_summary_methods(class_attributes: dict[str, Any], **kwargs: Any) -> None: ...
def bucket_load(self: Any, *args: Any, **kwargs: Any) -> None: ...
def object_summary_load(self: Any, *args: Any, **kwargs: Any) -> None: ...
def upload_file(
    self: Any,
    Filename: str,
    Bucket: str,
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def download_file(
    self: Any,
    Bucket: str,
    Key: str,
    Filename: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def bucket_upload_file(
    self: Any,
    Filename: str,
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def bucket_download_file(
    self: Any,
    Key: str,
    Filename: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def object_upload_file(
    self: Any,
    Filename: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def object_download_file(
    self: Any,
    Filename: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def copy(
    self: Any,
    CopySource: dict[str, Any],
    Bucket: str,
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    SourceClient: BaseClient | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def bucket_copy(
    self: Any,
    CopySource: dict[str, Any],
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    SourceClient: BaseClient | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def object_copy(
    self: Any,
    CopySource: dict[str, Any],
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    SourceClient: BaseClient | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def upload_fileobj(
    self: Any,
    Fileobj: IO[Any],
    Bucket: str,
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def bucket_upload_fileobj(
    self: Any,
    Fileobj: IO[Any],
    Key: str,
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def object_upload_fileobj(
    self: Any,
    Fileobj: IO[Any],
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def download_fileobj(
    self: Any,
    Bucket: str,
    Key: str,
    Fileobj: IO[Any],
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def bucket_download_fileobj(
    self: Any,
    Key: str,
    Fileobj: IO[Any],
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
def object_download_fileobj(
    self: Any,
    Fileobj: IO[Any],
    ExtraArgs: dict[str, Any] | None = ...,
    Callback: Callable[..., Any] | None = ...,
    Config: TransferConfig | None = ...,
) -> None: ...
