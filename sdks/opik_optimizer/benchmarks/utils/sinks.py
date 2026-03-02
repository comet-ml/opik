from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

# NOTE: This module intentionally contains event sinks only.
# TODO(benchmarks): add persistence sink interfaces (e.g., local file sink,
# Modal volume sink) and route engine read/write operations through them.


@dataclass(frozen=True)
class BenchmarkEvent:
    """Structured lifecycle event emitted by benchmark runners."""

    name: str
    payload: dict[str, Any] = field(default_factory=dict)


class EventSink(Protocol):
    """Interface for handling benchmark lifecycle events."""

    def emit(self, event: BenchmarkEvent) -> None: ...


class NullSink:
    """No-op event sink used as a default when no sink is configured."""

    def emit(self, event: BenchmarkEvent) -> None:
        del event


class ListSink:
    """In-memory event sink useful for tests and local debugging."""

    def __init__(self) -> None:
        self.events: list[BenchmarkEvent] = []

    def emit(self, event: BenchmarkEvent) -> None:
        self.events.append(event)
