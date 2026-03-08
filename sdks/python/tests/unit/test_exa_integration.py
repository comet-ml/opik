from __future__ import annotations

from typing import Any, Callable

import opik

from opik.integrations.exa import track_exa


class _DummyExaClient:
    def search(self, query: str) -> dict[str, Any]:
        return {"results": [{"title": query}]}

    def search_and_contents(self, query: str) -> dict[str, Any]:
        return {"results": [{"text": query}]}

    def find_similar(self, url: str) -> dict[str, Any]:
        return {"results": [{"url": url}]}

    def answer(self, question: str) -> dict[str, Any]:
        return {"answer": question}

    def not_tracked(self) -> str:
        return "ok"


def test_track_exa__patches_supported_methods_with_search_metadata(monkeypatch: Any) -> None:
    tracked_calls: list[dict[str, Any]] = []

    def fake_track(**track_kwargs: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            def wrapped(*args: Any, **kwargs: Any) -> Any:
                tracked_calls.append(track_kwargs)
                return func(*args, **kwargs)

            return wrapped

        return decorator

    monkeypatch.setattr(opik, "track", fake_track)

    client = _DummyExaClient()
    tracked_client = track_exa(client, project_name="exa-test-project")

    assert tracked_client is client

    assert tracked_client.search("latest ai")["results"][0]["title"] == "latest ai"
    assert tracked_client.search_and_contents("agents")["results"][0]["text"] == "agents"
    assert tracked_client.find_similar("https://exa.ai")["results"][0]["url"] == "https://exa.ai"
    assert tracked_client.answer("what is exa?")["answer"] == "what is exa?"
    assert tracked_client.not_tracked() == "ok"

    assert len(tracked_calls) == 4
    for call in tracked_calls:
        assert call["type"] == "tool"
        assert call["project_name"] == "exa-test-project"
        assert call["metadata"]["opik.kind"] == "search"
        assert call["metadata"]["opik.provider"] == "exa"
        assert call["metadata"]["opik.operation"] in {
            "search",
            "search_and_contents",
            "find_similar",
            "answer",
        }


def test_track_exa__second_patch_is_noop(monkeypatch: Any) -> None:
    decorated_method_ids: list[int] = []

    def fake_track(**_track_kwargs: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            def wrapped(*args: Any, **kwargs: Any) -> Any:
                return func(*args, **kwargs)

            return wrapped

        return decorator

    monkeypatch.setattr(opik, "track", fake_track)

    client = _DummyExaClient()
    track_exa(client)
    decorated_method_ids.append(id(client.search))

    track_exa(client)
    decorated_method_ids.append(id(client.search))

    assert decorated_method_ids[0] == decorated_method_ids[1]
