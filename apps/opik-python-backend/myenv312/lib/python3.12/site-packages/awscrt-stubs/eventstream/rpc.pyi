"""
Type annotations for awscrt.eventstream.rpc module.

Copyright 2024 Vlad Emelianov
"""

import abc
from abc import ABC, abstractmethod
from concurrent.futures import Future
from enum import IntEnum
from typing import Any, Callable, Sequence

from awscrt import NativeResource
from awscrt.eventstream import Header
from awscrt.http import HttpClientConnection
from awscrt.io import ClientBootstrap, SocketOptions, TlsConnectionOptions

__all__ = [
    "ClientConnection",
    "ClientConnectionHandler",
    "ClientContinuation",
    "ClientContinuationHandler",
    "MessageFlag",
    "MessageType",
]

class MessageType(IntEnum):
    APPLICATION_MESSAGE = 0
    APPLICATION_ERROR = 1
    PING = 2
    PING_RESPONSE = 3
    CONNECT = 4
    CONNECT_ACK = 5
    PROTOCOL_ERROR = 6
    INTERNAL_ERROR = 7
    def __format__(self, format_spec: str) -> str: ...

class MessageFlag:
    NONE: int = ...
    CONNECTION_ACCEPTED: int = ...
    TERMINATE_STREAM: int = ...
    def __format__(self, format_spec: str) -> str: ...

class ClientConnectionHandler(ABC, metaclass=abc.ABCMeta):
    @abstractmethod
    def on_connection_setup(
        self,
        connection: HttpClientConnection | None,
        error: BaseException | None,
        **kwargs: Any,
    ) -> None: ...
    @abstractmethod
    def on_connection_shutdown(self, reason: BaseException | None, **kwargs: Any) -> None: ...
    @abstractmethod
    def on_protocol_message(
        self,
        headers: Sequence[Header],
        payload: bytes,
        message_type: MessageType,
        flags: int,
        **kwargs: Any,
    ) -> None: ...

class ClientConnection(NativeResource):
    def __init__(self, host_name: str, port: int, handler: ClientConnectionHandler) -> None:
        self.host_name: str
        self.port: int
        self.shutdown_future: Future[None]

    @classmethod
    def connect(
        cls,
        *,
        handler: ClientConnectionHandler,
        host_name: str,
        port: int,
        bootstrap: ClientBootstrap | None = ...,
        socket_options: SocketOptions | None = ...,
        tls_connection_options: TlsConnectionOptions | None = ...,
    ) -> Future[BaseException | None]: ...
    def close(self) -> Future[BaseException | None]: ...
    def is_open(self) -> bool: ...
    def send_protocol_message(
        self,
        *,
        headers: Sequence[Header] | None = ...,
        payload: bytes | None = ...,
        message_type: MessageType,
        flags: int | None = ...,
        on_flush: Callable[..., Any] | None = ...,
    ) -> Future[BaseException | None]: ...
    def new_stream(self, handler: ClientContinuationHandler) -> ClientContinuation: ...

class ClientContinuation(NativeResource):
    def __init__(self, handler: ClientConnectionHandler, connection: ClientConnection) -> None:
        self.connection: ClientConnection
        self.closed_future: Future[None]

    def activate(
        self,
        *,
        operation: str,
        headers: Sequence[Header] | None = ...,
        payload: bytes | None = ...,
        message_type: MessageType,
        flags: int | None = ...,
        on_flush: Callable[..., Any] | None = ...,
    ) -> Future[BaseException | None]: ...
    def send_message(
        self,
        *,
        headers: Sequence[Header] | None = ...,
        payload: bytes | None = ...,
        message_type: MessageType,
        flags: int | None = ...,
        on_flush: Callable[..., Any] | None = ...,
    ) -> Future[BaseException | None]: ...
    def is_closed(self) -> bool: ...

class ClientContinuationHandler(ABC, metaclass=abc.ABCMeta):
    @abstractmethod
    def on_continuation_message(
        self,
        headers: Sequence[Header],
        payload: bytes,
        message_type: MessageType,
        flags: int,
        **kwargs: Any,
    ) -> None: ...
    @abstractmethod
    def on_continuation_closed(self, **kwargs: Any) -> None: ...
