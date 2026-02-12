from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass(frozen=True)
class BenchmarkEvent:
    name: str
    payload: dict[str, Any] = field(default_factory=dict)


class EventSink(Protocol):
    def emit(self, event: BenchmarkEvent) -> None: ...


class NullSink:
    def emit(self, event: BenchmarkEvent) -> None:
        del event


class ListSink:
    def __init__(self) -> None:
        self.events: list[BenchmarkEvent] = []

    def emit(self, event: BenchmarkEvent) -> None:
        self.events.append(event)
