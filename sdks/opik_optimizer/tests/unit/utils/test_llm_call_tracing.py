# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: every optimizer LLM call lands in an attributable trace.

`call_model` used to hand its params to `opik_monitor`, which injected an
`OpikLogger` next to our own `track_completion`: the same call was logged twice
whenever a span was open, and since the backend prices spans, the duplicate
doubled the reported cost. The logger was also the only thing carrying the
optimization tags onto a trace, so it could not simply be dropped — tracing and
attribution had to move here instead.
"""

from typing import Any

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core import llm_calls
from opik_optimizer.core.state import OptimizationContext


@pytest.fixture(autouse=True)
def _no_live_opik_traces(monkeypatch: pytest.MonkeyPatch) -> None:
    """Keep the unit suite off the network.

    The dispatchers open a real trace through ``opik.track``; without this the
    tests emit spans to whatever backend the environment happens to point at.
    The decorator is replaced by a pass-through, which is all these tests need —
    what they assert is the params handed to LiteLLM and the tags requested via
    ``update_current_trace``, both patched per test.
    """

    def _passthrough(*_args: Any, **_kwargs: Any) -> Any:
        def _decorator(func: Any) -> Any:
            return func

        return _decorator

    monkeypatch.setattr(llm_calls.opik, "track", _passthrough)


class _Optimizer(BaseOptimizer):
    def __init__(self) -> None:
        super().__init__(model="dummy", verbose=0)

    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
        raise NotImplementedError

    def run_optimization(self, context: OptimizationContext) -> Any:
        raise NotImplementedError

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        return {}


class TestResolveOptimizationId:
    def test_explicit_id_always_wins(self) -> None:
        assert llm_calls._resolve_optimization_id("explicit") == "explicit"

    def test_falls_back_to_the_running_optimizer(self) -> None:
        """13 of 20 call sites never pass an id — every Evolutionary and
        HierarchicalReflective LLM call among them — so their traces carried no
        optimization tag and the run's cost aggregation never counted them."""
        optimizer = _Optimizer()
        optimizer.current_optimization_id = "opt-from-stack"

        def ops_style_call() -> str | None:  # a module-level ops helper
            return llm_calls._resolve_optimization_id(None)

        def method(self: Any) -> str | None:
            return ops_style_call()

        _Optimizer.probe = method  # type: ignore[attr-defined]
        assert optimizer.probe() == "opt-from-stack"  # type: ignore[attr-defined]

    def test_none_when_no_optimizer_is_running(self) -> None:
        assert llm_calls._resolve_optimization_id(None) is None


class TestInvokeTraced:
    @staticmethod
    def _params() -> dict[str, Any]:
        return {
            "metadata": {
                "opik_call_type": "candidate_generation",
                "opik": {
                    "tags": ["opt-1", "Prompt Optimization"],
                    # track_completion already sets the project; leaving this in
                    # the LiteLLM params makes the two disagree.
                    "project_name": "p",
                },
            }
        }

    def test_span_open__call_nests_under_it_instead_of_forking_a_trace(
        self, monkeypatch
    ) -> None:
        span = object()
        monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: span)
        seen: dict[str, Any] = {}

        def fake_completion(**kwargs: Any) -> str:
            seen.update(kwargs)
            return "ok"

        assert (
            llm_calls._invoke_traced(
                fake_completion, self._params(), project_name="p", model="m"
            )
            == "ok"
        )
        assert seen["metadata"]["opik"]["current_span_data"] is span

    def test_no_span__tags_are_stamped_on_the_trace_we_open(self, monkeypatch) -> None:
        monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: None)
        tagged: list[list[str]] = []
        monkeypatch.setattr(
            llm_calls.opik_context,
            "update_current_trace",
            lambda **kw: tagged.append(kw["tags"]),
        )

        llm_calls._invoke_traced(
            lambda **kwargs: "ok", self._params(), project_name="p", model="m"
        )

        assert tagged == [["opt-1", "Prompt Optimization"]]

    def test_no_span__call_still_runs_when_tagging_fails(self, monkeypatch) -> None:
        """Attribution is best-effort: losing a tag must not fail the call."""
        monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: None)

        def boom(**kwargs: Any) -> None:
            raise RuntimeError("tagging is down")

        monkeypatch.setattr(llm_calls.opik_context, "update_current_trace", boom)

        assert (
            llm_calls._invoke_traced(
                lambda **kwargs: "ok", self._params(), project_name="p", model="m"
            )
            == "ok"
        )

    def test_span_open__project_name_is_stripped_but_tags_survive(
        self, monkeypatch
    ) -> None:
        monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: object())
        seen: dict[str, Any] = {}

        llm_calls._invoke_traced(
            lambda **kwargs: seen.update(kwargs),
            self._params(),
            project_name="p",
            model="m",
        )

        opik_metadata = seen["metadata"]["opik"]
        assert "project_name" not in opik_metadata
        assert opik_metadata["tags"] == ["opt-1", "Prompt Optimization"]

    def test_no_span__project_name_is_stripped_but_tags_survive(
        self, monkeypatch
    ) -> None:
        monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: None)
        monkeypatch.setattr(
            llm_calls.opik_context, "update_current_trace", lambda **kw: None
        )
        seen: dict[str, Any] = {}

        llm_calls._invoke_traced(
            lambda **kwargs: seen.update(kwargs),
            self._params(),
            project_name="p",
            model="m",
        )

        opik_metadata = seen["metadata"]["opik"]
        assert "project_name" not in opik_metadata
        assert opik_metadata["tags"] == ["opt-1", "Prompt Optimization"]

    def test_explicit_caller_span_hint_is_not_overwritten(self) -> None:
        caller_span = object()
        params = {"metadata": {"opik": {"current_span_data": caller_span}}}
        assert (
            llm_calls._nest_under_current_span(params, object())["metadata"]["opik"][
                "current_span_data"
            ]
            is caller_span
        )


class TestTraceName:
    def test_named_after_the_call_type(self) -> None:
        assert (
            llm_calls._trace_name({"metadata": {"opik_call_type": "reflection"}})
            == "reflection"
        )

    def test_falls_back_when_the_call_type_is_absent(self) -> None:
        assert llm_calls._trace_name({}) == "optimizer_llm_call"


@pytest.mark.asyncio
async def test_async_path_awaits_inside_the_trace(monkeypatch) -> None:
    """A sync wrapper would close the trace on returning the coroutine — before
    the call it is timing has run."""
    monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: None)
    monkeypatch.setattr(
        llm_calls.opik_context, "update_current_trace", lambda **kw: None
    )

    async def fake_acompletion(**kwargs: Any) -> str:
        return "async-ok"

    result = await llm_calls._invoke_traced_async(
        fake_acompletion, {"metadata": {}}, project_name="p", model="m"
    )
    assert result == "async-ok"


@pytest.mark.asyncio
async def test_async_path_nests_under_an_open_span(monkeypatch) -> None:
    """The async dispatcher must set the same nesting hint as the sync one;
    without it the call forks a second, detached trace."""
    span = object()
    monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: span)
    seen: dict[str, Any] = {}

    async def fake_acompletion(**kwargs: Any) -> str:
        seen.update(kwargs)
        return "async-ok"

    params = {
        "metadata": {
            "opik_call_type": "reflection",
            "opik": {"tags": ["opt-1"], "project_name": "p"},
        }
    }
    result = await llm_calls._invoke_traced_async(
        fake_acompletion, params, project_name="p", model="m"
    )

    assert result == "async-ok"
    assert seen["metadata"]["opik"]["current_span_data"] is span
    assert "project_name" not in seen["metadata"]["opik"]
    assert seen["metadata"]["opik"]["tags"] == ["opt-1"]


@pytest.mark.asyncio
async def test_async_path_tags_the_trace_it_opens(monkeypatch) -> None:
    monkeypatch.setattr(llm_calls, "_current_span_or_none", lambda: None)
    tagged: list[list[str]] = []
    monkeypatch.setattr(
        llm_calls.opik_context,
        "update_current_trace",
        lambda **kw: tagged.append(kw["tags"]),
    )

    async def fake_acompletion(**kwargs: Any) -> str:
        return "async-ok"

    await llm_calls._invoke_traced_async(
        fake_acompletion,
        {"metadata": {"opik": {"tags": ["opt-async", "Prompt Optimization"]}}},
        project_name="p",
        model="m",
    )

    assert tagged == [["opt-async", "Prompt Optimization"]]
