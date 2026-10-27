"""The optimizer's own LLM calls are logged as optimization traces."""

from __future__ import annotations

from typing import Any

import opik

from opik_optimizer.core import llm_calls


def test_optimizer_llm_call_trace_has_optimization_source() -> None:
    def completion(**_kwargs: Any) -> str:
        return "ok"

    previous = opik.is_tracing_active()
    opik.set_tracing_active(True)
    try:
        with opik.record_traces_locally() as storage:
            llm_calls._invoke_traced(
                completion,
                {"metadata": {"opik_call_type": "reasoning"}},
                project_name="trace-source-test",
            )
            traces = storage.trace_trees
    finally:
        opik.set_tracing_active(previous)

    # The local store is process-wide; look only at this call's trace.
    ours = [trace for trace in traces if trace.project_name == "trace-source-test"]
    assert [trace.source for trace in ours] == ["optimization"]
