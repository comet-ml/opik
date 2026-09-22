# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: the tracing boundary an optimization run's cost depends on.

The backend attributes optimizer-internal spend to a run by the optimization id
on the TRACE, and prices the call from the span's model/provider/usage. Both
halves live outside the SDK, so unit tests can only pin the params we hand to
LiteLLM — this asserts the record that actually reached the backend.

litellm's `mock_response` stands in for the provider, so the whole tracing stack
runs without spending anything on a model.
"""

from __future__ import annotations

import time
import uuid
from typing import Any

import opik
import pytest

from opik_optimizer.core import llm_calls

pytestmark = pytest.mark.e2e


def _configured() -> bool:
    try:
        return bool(opik.config.OpikConfig().api_key)
    except Exception:
        return False


@pytest.mark.skipif(not _configured(), reason="requires Opik credentials")
def test_optimizer_llm_call__lands_in_one_trace_tagged_with_the_optimization_id(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    project = f"e2e-optimizer-attribution-{uuid.uuid4().hex[:8]}"
    optimization_id = f"opt-{uuid.uuid4().hex[:12]}"
    monkeypatch.setenv("OPIK_PROJECT_NAME", project)

    llm_calls.call_model(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": "ping"}],
        optimization_id=optimization_id,
        project_name=project,
        metadata={"opik_call_type": "e2e_attribution"},
        model_parameters={"mock_response": "pong"},
    )

    client = opik.Opik(project_name=project)
    client.flush()

    traces: list[Any] = []
    for _ in range(20):
        # Ingestion is asynchronous, and until the first trace lands the project
        # itself does not exist — the search 404s rather than returning empty.
        try:
            traces = [
                t
                for t in client.search_traces(project_name=project, max_results=50)
                if optimization_id in (t.tags or [])
            ]
        except Exception:
            traces = []
        if traces:
            break
        time.sleep(3)

    assert traces, (
        f"no trace carried the optimization id {optimization_id}; without that tag "
        "the backend cannot attribute this call's cost to the run"
    )
    trace = traces[0]
    assert trace.name == "e2e_attribution"  # named after the call type
    # Exactly one: a second LLM span here is the duplicate OpikLogger regression,
    # and it would double the cost the backend computes for the run.
    assert trace.llm_span_count == 1
