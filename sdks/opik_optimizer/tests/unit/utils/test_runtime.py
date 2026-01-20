from __future__ import annotations

import signal
from typing import Any

from opik_optimizer import ChatPrompt
from opik_optimizer.core import runtime
from tests.unit.test_helpers import make_optimization_context


class _ImmediateThread:
    def __init__(
        self, target: Any, args: tuple[Any, ...] = (), daemon: bool | None = None
    ):
        self._target = target
        self._args = args

    def start(self) -> None:
        self._target(*self._args)

    def join(self, timeout: float | None = None) -> None:
        return None


class _NoopTimer:
    def __init__(self, *_args: Any, **_kwargs: Any) -> None:
        pass

    def start(self) -> None:
        return None


class _DummyOptimizer:
    def __init__(self) -> None:
        self.calls: list[tuple[Any, str]] = []

    def _finalize_optimization(self, context: Any, status: str = "completed") -> None:
        self.calls.append((context, status))


def test_handle_termination_marks_cancelled(monkeypatch) -> None:
    prompt = ChatPrompt(system="sys", user="{question}")
    context = make_optimization_context(prompt)
    optimizer = _DummyOptimizer()

    handlers: dict[int, Any] = {}

    def _fake_getsignal(sig: int) -> Any:
        return f"prev-{sig}"

    def _fake_signal(sig: int, handler: Any) -> None:
        handlers[sig] = handler

    monkeypatch.setattr(signal, "getsignal", _fake_getsignal)
    monkeypatch.setattr(signal, "signal", _fake_signal)
    monkeypatch.setattr(runtime.os, "_exit", lambda _code: None)
    monkeypatch.setattr(runtime.threading, "Thread", _ImmediateThread)
    monkeypatch.setattr(runtime.threading, "Timer", _NoopTimer)

    with runtime.handle_termination(optimizer=optimizer, context=context):
        assert signal.SIGTERM in handlers
        handlers[signal.SIGTERM](signal.SIGTERM, None)

    assert context.should_stop is True
    assert context.finish_reason == "cancelled"
    assert optimizer.calls == [(context, "cancelled")]
