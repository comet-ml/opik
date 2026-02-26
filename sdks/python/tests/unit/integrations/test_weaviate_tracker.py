from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List

from opik.integrations.weaviate.opik_tracker import track_weaviate


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


def _build_weaviate_collection() -> Any:
    class Query:
        def hybrid(self) -> Dict[str, Any]:
            return {"ok": True}

        def near_text(self) -> Dict[str, Any]:
            return {"ok": True}

    class Collection:
        query = Query()

    return Collection()


def test_track_weaviate__patches_nested_query_methods(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    collection = _build_weaviate_collection()
    tracked_collection = track_weaviate(collection, project_name="proj")

    assert tracked_collection is collection
    assert tracked_collection.opik_tracked is True
    assert len(track_spy.calls.values) == 2

    operation_names = {call["name"] for call in track_spy.calls.values}
    assert operation_names == {"weaviate.hybrid", "weaviate.near_text"}

    for call in track_spy.calls.values:
        assert call["type"] == "tool"
        assert call["metadata"]["opik.kind"] == "retrieval"
        assert call["metadata"]["opik.provider"] == "weaviate"
        assert "opik.operation" in call["metadata"]


def test_track_weaviate__idempotent(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    collection = _build_weaviate_collection()
    track_weaviate(collection)
    calls_after_first = len(track_spy.calls.values)
    track_weaviate(collection)

    assert len(track_spy.calls.values) == calls_after_first
