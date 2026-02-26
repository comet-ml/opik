from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List

from opik.integrations.chroma.opik_tracker import track_chroma
from opik.integrations.pinecone.opik_tracker import track_pinecone
from opik.integrations.qdrant.opik_tracker import track_qdrant
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


def _build_qdrant_client() -> Any:
    class Client:
        def search(self) -> Dict[str, Any]:
            return {"ok": True}

        def query_points(self) -> Dict[str, Any]:
            return {"ok": True}

    return Client()


def _build_pinecone_index() -> Any:
    class Index:
        def query(self) -> Dict[str, Any]:
            return {"ok": True}

    return Index()


def _build_chroma_collection() -> Any:
    class Collection:
        def query(self) -> Dict[str, Any]:
            return {"ok": True}

        def get(self) -> Dict[str, Any]:
            return {"ok": True}

    return Collection()


def _build_weaviate_like() -> Any:
    class Query:
        def hybrid(self) -> Dict[str, Any]:
            return {"ok": True}

    class Collection:
        query = Query()

    return Collection()


def _assert_required_metadata(metadata: Dict[str, Any], provider: str) -> None:
    assert metadata["opik.kind"] == "retrieval"
    assert metadata["opik.provider"] == provider
    assert "opik.operation" in metadata


def test_track_qdrant__patches_methods_with_retrieval_metadata(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    client = _build_qdrant_client()
    tracked_client = track_qdrant(client, project_name="proj")

    assert tracked_client is client
    assert tracked_client.opik_tracked is True
    assert len(track_spy.calls.values) >= 2

    for call in track_spy.calls.values:
        assert call["type"] == "tool"
        _assert_required_metadata(call["metadata"], "qdrant")


def test_track_weaviate__patches_nested_query_methods(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    collection = _build_weaviate_like()
    tracked = track_weaviate(collection)

    assert tracked is collection
    assert tracked.opik_tracked is True
    assert len(track_spy.calls.values) >= 1
    _assert_required_metadata(track_spy.calls.values[0]["metadata"], "weaviate")


def test_track_pinecone_and_chroma__idempotent(monkeypatch: Any) -> None:
    import opik.integrations._retrieval_tracker as retrieval_tracker

    track_spy = _TrackSpy()
    monkeypatch.setattr(retrieval_tracker.opik, "track", track_spy)

    pinecone_index = _build_pinecone_index()
    chroma_collection = _build_chroma_collection()

    track_pinecone(pinecone_index)
    initial_calls = len(track_spy.calls.values)
    track_pinecone(pinecone_index)
    assert len(track_spy.calls.values) == initial_calls

    track_chroma(chroma_collection)
    assert len(track_spy.calls.values) > initial_calls
    for call in track_spy.calls.values[initial_calls:]:
        _assert_required_metadata(call["metadata"], "chroma")
