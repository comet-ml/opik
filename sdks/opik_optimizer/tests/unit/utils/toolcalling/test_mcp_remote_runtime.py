import asyncio
from typing import Any

import pytest

from opik_optimizer.utils.toolcalling.runtime import mcp_remote


def test_start_client__cleans_up_transport_and_session_on_initialize_error(
    monkeypatch: Any,
) -> None:
    class FakeTransport:
        exited = False

        async def __aenter__(self) -> tuple[object, object, object]:
            return object(), object(), object()

        async def __aexit__(self, *_args: Any) -> None:
            self.exited = True

    class FakeSession:
        exited = False

        def __init__(self, _read: object, _write: object) -> None:
            _ = _read, _write

        async def __aenter__(self) -> "FakeSession":
            return self

        async def initialize(self) -> None:
            raise RuntimeError("session init failed")

        async def __aexit__(self, *_args: Any) -> None:
            self.exited = True

    transport = FakeTransport()
    session_holder: dict[str, FakeSession] = {}

    def _fake_streamable_http_client(_url: str, **_kwargs: Any) -> FakeTransport:
        return transport

    def _fake_load_remote_sdk() -> tuple[type[FakeSession], Any]:
        class TrackingSession(FakeSession):
            def __init__(self, read: object, write: object) -> None:
                super().__init__(read, write)
                session_holder["session"] = self

        return TrackingSession, _fake_streamable_http_client

    monkeypatch.setattr(mcp_remote, "_load_remote_sdk", _fake_load_remote_sdk)

    with pytest.raises(RuntimeError, match="session init failed"):
        asyncio.run(mcp_remote._start_client("https://mcp.example.com", {}, None))

    assert transport.exited is True
    assert session_holder["session"].exited is True
