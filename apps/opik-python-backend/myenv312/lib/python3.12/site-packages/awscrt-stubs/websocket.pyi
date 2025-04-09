"""
Type annotations for awscrt.websocket module.

Copyright 2024 Vlad Emelianov
"""

from dataclasses import dataclass
from enum import IntEnum
from typing import Any, Callable, Sequence

from awscrt import NativeResource as NativeResource
from awscrt.http import HttpProxyOptions as HttpProxyOptions
from awscrt.http import HttpRequest as HttpRequest
from awscrt.io import ClientBootstrap as ClientBootstrap
from awscrt.io import SocketOptions as SocketOptions
from awscrt.io import TlsConnectionOptions as TlsConnectionOptions

class Opcode(IntEnum):
    CONTINUATION = 0x0
    TEXT = 0x1
    BINARY = 0x2
    CLOSE = 0x8
    PING = 0x9
    PONG = 0xA
    def is_data_frame(self) -> bool: ...

MAX_PAYLOAD_LENGTH: int

@dataclass
class OnConnectionSetupData:
    exception: Exception | None = ...
    websocket: WebSocket | None = ...
    handshake_response_status: int | None = ...
    handshake_response_headers: Sequence[tuple[str, str]] | None = ...
    handshake_response_body: bytes | None = ...

@dataclass
class OnConnectionShutdownData:
    exception: Exception | None = ...

@dataclass
class IncomingFrame:
    opcode: Opcode
    payload_length: int
    fin: bool
    def is_data_frame(self) -> bool: ...

@dataclass
class OnIncomingFrameBeginData:
    frame: IncomingFrame

@dataclass
class OnIncomingFramePayloadData:
    frame: IncomingFrame
    data: bytes

@dataclass
class OnIncomingFrameCompleteData:
    frame: IncomingFrame
    exception: Exception | None = ...

@dataclass
class OnSendFrameCompleteData:
    exception: Exception | None = ...

class WebSocket(NativeResource):
    def __init__(self, binding: Any) -> None: ...
    def close(self) -> None: ...
    def send_frame(
        self,
        opcode: Opcode,
        payload: str | bytes | bytearray | memoryview | None = ...,
        *,
        fin: bool = ...,
        on_complete: Callable[[OnSendFrameCompleteData], None] | None = ...,
    ) -> None: ...
    def increment_read_window(self, size: int) -> None: ...

class _WebSocketCore(NativeResource):
    def __init__(
        self,
        on_connection_setup: Callable[[OnConnectionSetupData], None],
        on_connection_shutdown: Callable[[OnConnectionShutdownData], None] | None,
        on_incoming_frame_begin: Callable[[OnIncomingFrameBeginData], None] | None,
        on_incoming_frame_payload: Callable[[OnIncomingFramePayloadData], None] | None,
        on_incoming_frame_complete: Callable[[OnIncomingFrameCompleteData], None] | None,
    ) -> None: ...

def connect(
    *,
    host: str,
    port: int | None = ...,
    handshake_request: HttpRequest,
    bootstrap: ClientBootstrap | None = ...,
    socket_options: SocketOptions | None = ...,
    tls_connection_options: TlsConnectionOptions | None = ...,
    proxy_options: HttpProxyOptions | None = ...,
    manage_read_window: bool = ...,
    initial_read_window: int | None = ...,
    on_connection_setup: Callable[[OnConnectionSetupData], None],
    on_connection_shutdown: Callable[[OnConnectionShutdownData], None] | None = ...,
    on_incoming_frame_begin: Callable[[OnIncomingFrameBeginData], None] | None = ...,
    on_incoming_frame_payload: Callable[[OnIncomingFramePayloadData], None] | None = ...,
    on_incoming_frame_complete: Callable[[OnIncomingFrameCompleteData], None] | None = ...,
) -> None: ...
def create_handshake_request(*, host: str, path: str = ...) -> HttpRequest: ...
