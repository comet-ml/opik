"""
Type annotations for botocore.monitoring module.

Copyright 2025 Vlad Emelianov
"""

import socket
from logging import Logger
from typing import Any, Callable, Sequence

from botocore.compat import ensure_bytes as ensure_bytes
from botocore.compat import ensure_unicode as ensure_unicode
from botocore.compat import urlparse as urlparse
from botocore.hooks import BaseEventHooks
from botocore.model import OperationModel

logger: Logger = ...

class Monitor:
    def __init__(self, adapter: MonitorEventAdapter, publisher: SocketPublisher) -> None: ...
    def register(self, event_emitter: BaseEventHooks) -> None: ...
    def capture(self, event_name: str, **payload: Any) -> None: ...

class MonitorEventAdapter:
    def __init__(self, time: Callable[[], float] = ...) -> None: ...
    def feed(
        self, emitter_event_name: str, emitter_payload: dict[str, Any]
    ) -> BaseMonitorEvent: ...

class BaseMonitorEvent:
    def __init__(self, service: str, operation: str, timestamp: int) -> None:
        self.service: str = ...
        self.operation: str = ...
        self.timestamp: int = ...

    def __eq__(self, other: object) -> bool: ...

class APICallEvent(BaseMonitorEvent):
    def __init__(
        self,
        service: str,
        operation: OperationModel,
        timestamp: int,
        latency: int | None = ...,
        attempts: Sequence[APICallAttemptEvent] | None = ...,
        retries_exceeded: bool = ...,
    ) -> None:
        self.latency: int = ...
        self.attempts: Sequence[APICallAttemptEvent] = ...
        self.retries_exceeded: bool = ...

    def new_api_call_attempt(self, timestamp: int) -> APICallAttemptEvent: ...

class APICallAttemptEvent(BaseMonitorEvent):
    def __init__(
        self,
        service: str,
        operation: OperationModel,
        timestamp: int,
        latency: int | None = ...,
        url: str | None = ...,
        http_status_code: int | None = ...,
        request_headers: dict[str, Any] | None = ...,
        response_headers: dict[str, Any] | None = ...,
        parsed_error: dict[str, Any] | None = ...,
        wire_exception: Exception | None = ...,
    ) -> None:
        self.latency: int | None = ...
        self.url: str | None = ...
        self.http_status_code: int | None = ...
        self.request_headers: dict[str, Any] | None = ...
        self.response_headers: dict[str, Any] | None = ...
        self.parsed_error: dict[str, Any] | None = ...
        self.wire_exception: Exception | None = ...

class CSMSerializer:
    def __init__(self, csm_client_id: str) -> None:
        self.csm_client_id: str = ...

    def serialize(self, event: BaseMonitorEvent) -> bytes: ...

class SocketPublisher:
    def __init__(
        self, socket: socket.socket, host: str, port: int, serializer: CSMSerializer
    ) -> None: ...
    def publish(self, event: BaseMonitorEvent) -> None: ...
