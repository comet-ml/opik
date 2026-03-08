from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List

from opik.integrations.chroma.opik_tracker import track_chroma


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


def _build_chroma_collection() -> Any:
    class Collection:
        def query(self) -> Dict[str, Any]:
            return {"ok": True}

        def get(self) -> Dict[str, Any]:
            return {"ok": True}

    return Collection()


def test_track_chroma__patches_methods_with_retrieval_metadata(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    collection = _build_chroma_collection()
    tracked_collection = track_chroma(collection, project_name="proj")

    assert tracked_collection is collection
    assert tracked_collection.opik_tracked is True
    assert len(track_spy.calls.values) == 2

    operation_names = {call["name"] for call in track_spy.calls.values}
    assert operation_names == {"chroma.query", "chroma.get"}

    for call in track_spy.calls.values:
        assert call["type"] == "tool"
        assert call["metadata"]["opik.kind"] == "retrieval"
        assert call["metadata"]["opik.provider"] == "chroma"
        assert "opik.operation" in call["metadata"]


def test_track_chroma__idempotent(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    collection = _build_chroma_collection()
    track_chroma(collection)
    calls_after_first = len(track_spy.calls.values)
    track_chroma(collection)

    assert len(track_spy.calls.values) == calls_after_first
