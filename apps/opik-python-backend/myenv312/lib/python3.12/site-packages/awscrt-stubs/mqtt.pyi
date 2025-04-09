"""
Type annotations for awscrt.mqtt module.

Copyright 2024 Vlad Emelianov
"""

from concurrent.futures import Future
from dataclasses import dataclass
from enum import IntEnum
from typing import Any, Callable

from awscrt import NativeResource as NativeResource
from awscrt.exceptions import AwsCrtError
from awscrt.http import HttpProxyOptions as HttpProxyOptions
from awscrt.http import HttpRequest as HttpRequest
from awscrt.io import ClientBootstrap as ClientBootstrap
from awscrt.io import ClientTlsContext as ClientTlsContext
from awscrt.io import SocketOptions as SocketOptions
from awscrt.mqtt5 import QoS as Mqtt5QoS

class QoS(IntEnum):
    AT_MOST_ONCE = 0
    AT_LEAST_ONCE = 1
    EXACTLY_ONCE = 2

    def to_mqtt5(self) -> Mqtt5QoS: ...

class ConnectReturnCode(IntEnum):
    ACCEPTED = 0
    UNACCEPTABLE_PROTOCOL_VERSION = 1
    IDENTIFIER_REJECTED = 2
    SERVER_UNAVAILABLE = 3
    BAD_USERNAME_OR_PASSWORD = 4
    NOT_AUTHORIZED = 5

class Will:
    def __init__(self, topic: str, qos: QoS, payload: bytes, retain: bool) -> None:
        self.topic: str
        self.qos: QoS
        self.payload: bytes
        self.retain: bool

@dataclass
class OnConnectionSuccessData:
    return_code: ConnectReturnCode | None = ...
    session_present: bool = ...

@dataclass
class OnConnectionFailureData:
    error: AwsCrtError | None = ...

@dataclass
class OnConnectionClosedData: ...

class Client(NativeResource):
    def __init__(
        self, bootstrap: ClientBootstrap | None = ..., tls_ctx: ClientTlsContext | None = ...
    ) -> None:
        self.tls_ctx: ClientTlsContext | None = ...

@dataclass
class OperationStatisticsData:
    incomplete_operation_count: int = ...
    incomplete_operation_size: int = ...
    unacked_operation_count: int = ...
    unacked_operation_size: int = ...

class Connection(NativeResource):
    def __init__(
        self,
        client: Client,
        host_name: str,
        port: int,
        client_id: str,
        clean_session: bool = ...,
        on_connection_interrupted: Callable[[Connection, AwsCrtError], None] | None = ...,
        on_connection_resumed: Callable[[Connection, ConnectReturnCode, bool], None] | None = ...,
        reconnect_min_timeout_secs: int = ...,
        reconnect_max_timeout_secs: int = ...,
        keep_alive_secs: int = ...,
        ping_timeout_ms: int = ...,
        protocol_operation_timeout_ms: int = ...,
        will: Will | None = ...,
        username: str | None = ...,
        password: str | None = ...,
        socket_options: SocketOptions | None = ...,
        use_websockets: bool = ...,
        websocket_proxy_options: HttpProxyOptions | None = ...,
        websocket_handshake_transform: Callable[[WebsocketHandshakeTransformArgs], None]
        | None = ...,
        proxy_options: HttpProxyOptions | None = ...,
        on_connection_success: Callable[[Connection], OnConnectionSuccessData] | None = ...,
        on_connection_failure: Callable[[Connection], OnConnectionFailureData] | None = ...,
        on_connection_closed: Callable[[Connection], OnConnectionClosedData] | None = ...,
    ) -> None:
        self.client: Client
        self.client_id: str
        self.host_name: str
        self.port: int
        self.clean_session: bool
        self.reconnect_min_timeout_secs: int
        self.reconnect_max_timeout_secs: int
        self.keep_alive_secs: int
        self.ping_timeout_ms: int
        self.protocol_operation_timeout_ms: int
        self.will: Will
        self.username: str
        self.password: str
        self.socket_options: SocketOptions | None
        self.proxy_options: HttpProxyOptions | None

    def connect(self) -> Future[BaseException | None]: ...
    def reconnect(self) -> Future[BaseException | None]: ...
    def disconnect(self) -> Future[BaseException | None]: ...
    def subscribe(
        self,
        topic: str,
        qos: QoS,
        callback: Callable[[str, bytes, bool, QoS, bool], None] | None = ...,
    ) -> tuple[Future[dict[str, Any] | None], int]: ...
    def on_message(self, callback: Callable[[str, bytes, bool, QoS, bool], None]) -> None: ...
    def unsubscribe(self, topic: str) -> tuple[Future[dict[str, Any] | None], int]: ...
    def resubscribe_existing_topics(self) -> tuple[Future[dict[str, Any] | None], int]: ...
    def publish(
        self, topic: str, payload: str | bytes | bytearray, qos: QoS, retain: bool = ...
    ) -> tuple[Future[dict[str, Any] | None], int]: ...
    def get_stats(self) -> OperationStatisticsData: ...

class WebsocketHandshakeTransformArgs:
    def __init__(
        self,
        mqtt_connection: Connection,
        http_request: HttpRequest,
        done_future: Future[BaseException | None],
    ) -> None:
        self.mqtt_connection: Connection
        self.http_request: HttpRequest

    def set_done(self, exception: BaseException | None = ...) -> None: ...

class SubscribeError(Exception): ...
