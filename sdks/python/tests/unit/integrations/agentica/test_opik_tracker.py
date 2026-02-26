import asyncio
import sys
import types
from typing import Any, Dict

import opik
import pytest

from opik.integrations.agentica import track_agentica


class _FakeUsage:
    def __init__(self, usage: Dict[str, Any]):
        self._usage = usage

    def model_dump(self, exclude_none: bool = True) -> Dict[str, Any]:
        del exclude_none
        return self._usage


class _FakeAgent:
    def __init__(self):
        self._Agent__model = "openai:gpt-5-mini"
        self._usage = _FakeUsage(
            {
                "input_tokens": 9,
                "output_tokens": 4,
                "total_tokens": 13,
                "input_tokens_details": {"cached_tokens": 3},
                "output_tokens_details": {"reasoning_tokens": 1},
            }
        )

    async def call(
        self,
        fst: str,
        snd: str | None = None,
        /,
        mcp: str | None = None,
        **scope: Any,
    ) -> str:
        del snd
        del mcp
        greeting = scope.get("greeting", "hello")
        return f"{greeting}:{fst}"

    def last_usage(self) -> _FakeUsage:
        return self._usage

    __call__ = call


@pytest.fixture
def fake_agentica_module(monkeypatch: pytest.MonkeyPatch):
    module = types.SimpleNamespace(Agent=_FakeAgent)
    monkeypatch.setitem(sys.modules, "agentica", module)
    return module


def test_track_agentica__call_is_tracked_with_usage_and_model(
    fake_backend,
    fake_agentica_module,
):
    del fake_agentica_module

    track_agentica(project_name="agentica-test-project")
    agent = _FakeAgent()

    result = asyncio.run(agent.call("summarize this", greeting="hi"))
    assert result == "hi:summarize this"

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    root_span = trace_tree.spans[0]

    assert trace_tree.name == "agentica_call"
    assert root_span.type == "llm"
    assert root_span.provider == "symbolica"
    assert root_span.model == "openai:gpt-5-mini"
    assert root_span.project_name == "agentica-test-project"
    assert root_span.usage["prompt_tokens"] == 9
    assert root_span.usage["completion_tokens"] == 4
    assert root_span.usage["total_tokens"] == 13
    assert root_span.usage["original_usage.input_tokens_details.cached_tokens"] == 3
    assert root_span.usage["original_usage.output_tokens_details.reasoning_tokens"] == 1


def test_track_agentica__double_init_is_idempotent(fake_agentica_module):
    del fake_agentica_module

    track_agentica()
    first_call = _FakeAgent.call

    track_agentica()
    second_call = _FakeAgent.call

    assert first_call is second_call


def test_track_agentica__legacy_dunder_call_is_tracked(
    fake_backend, fake_agentica_module
):
    del fake_agentica_module

    track_agentica()
    agent = _FakeAgent()

    result = asyncio.run(agent("echo"))
    assert result == "hello:echo"

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].name == "agentica_call"
