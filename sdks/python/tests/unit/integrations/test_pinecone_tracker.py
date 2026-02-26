from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List

from opik.integrations.pinecone.opik_tracker import track_pinecone


@dataclass
class _Calls:
    values: List[Dict[str, Any]]


class _TrackSpy:
    def __init__(self) -> None:
        self.calls = _Calls(values=[])

    def __call__(self, **track_kwargs: Any):  # type: ignore[override]
        self.calls.values.append(track_kwargs)

        def decorator(func: Any) -> Any:
            def wrapped(*args: Any, **kwargs: Any) -> Any:
                return func(*args, **kwargs)

            wrapped.opik_tracked = True
            return wrapped

        return decorator


def _build_index() -> Any:
    class Index:
        def query(self) -> Dict[str, Any]:
            return {"ok": True}

        def fetch(self) -> Dict[str, Any]:
            return {"ok": True}

    return Index()


def test_track_pinecone__patches_methods_with_retrieval_metadata(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    index = _build_index()
    tracked_index = track_pinecone(index, project_name="proj")

    assert tracked_index is index
    assert tracked_index.opik_tracked is True
    assert len(track_spy.calls.values) == 2

    operation_names = {call["name"] for call in track_spy.calls.values}
    assert operation_names == {"pinecone.query", "pinecone.fetch"}

    for call in track_spy.calls.values:
        assert call["type"] == "tool"
        assert call["metadata"]["opik.kind"] == "retrieval"
        assert call["metadata"]["opik.provider"] == "pinecone"
        assert "opik.operation" in call["metadata"]


def test_track_pinecone__idempotent(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    index = _build_index()
    track_pinecone(index)
    calls_after_first = len(track_spy.calls.values)
    track_pinecone(index)

    assert len(track_spy.calls.values) == calls_after_first
