"""High spans/traces ingestion rate scenarios."""

from typing import List, Set

import opik
from opik.rest_api.types.span_public import SpanPublic

from . import _helpers
from ._helpers import Metrics


def test_many_traces_one_span_each(metrics: Metrics, load_scale: float) -> None:
    """High trace count, low spans-per-trace, via ``@opik.track``.

    Mimics a user-facing handler (``handle_request``) that makes one
    downstream call (``downstream_call``). Both are ``@opik.track``-
    decorated so each invocation creates a trace with a nested span —
    the same shape an instrumented LLM app would emit.

    Volume: 100k traces × 1 span each ≈ 200k observations. Payloads are
    intentionally small (100 B) so the test stresses message count, not
    per-message size.

    Verifies every submitted trace id lands with required fields set.
    """
    trace_count: int = int(100_000 * load_scale)
    trace_input_bytes: int = 100
    downstream_output_bytes: int = 100
    project_name: str = _helpers.unique_project_name("many-traces")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["trace_input_bytes"] = trace_input_bytes
    metrics["downstream_output_bytes"] = downstream_output_bytes

    submitted_trace_ids: List[str] = []

    @opik.track
    def downstream_call(payload: str) -> str:
        return _helpers.random_text(downstream_output_bytes)

    @opik.track(project_name=project_name)
    def handle_request(prompt: str) -> str:
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return downstream_call(payload=prompt)

    with metrics.timer("logging"):
        for _ in range(trace_count):
            handle_request(prompt=_helpers.random_text(trace_input_bytes))
            _helpers.think_time()

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)


def test_many_spans_per_trace(metrics: Metrics, load_scale: float) -> None:
    """Moderate trace count, heavy span fan-out per trace, via context managers.

    Uses ``opik.start_as_current_trace`` and ``opik.start_as_current_span``
    — the pattern a user reaches for when they want explicit control over
    where a trace/span starts and ends rather than wrapping a function.

    Volume: 5k traces × 50 spans = 250k spans. Payloads are small
    (~100 B) so the test stresses span-batching and trace/span ordering
    guarantees more than raw byte volume.

    Verifies every submitted trace id lands with required fields set, and
    that the last trace's 50 spans are all visible and well-formed.
    """
    trace_count: int = int(5_000 * load_scale)
    spans_per_trace: int = 50
    trace_input_bytes: int = 100
    trace_output_bytes: int = 100
    span_input_bytes: int = 100
    span_output_bytes: int = 100
    project_name: str = _helpers.unique_project_name("many-spans")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["spans_per_trace"] = spans_per_trace
    metrics["trace_input_bytes"] = trace_input_bytes
    metrics["trace_output_bytes"] = trace_output_bytes
    metrics["span_input_bytes"] = span_input_bytes
    metrics["span_output_bytes"] = span_output_bytes

    submitted_trace_ids: List[str] = []
    last_trace_id: str = ""

    with metrics.timer("logging"):
        for _ in range(trace_count):
            with opik.start_as_current_trace(
                name="handle_request",
                project_name=project_name,
                input={"prompt": _helpers.random_text(trace_input_bytes)},
                output={"completion": _helpers.random_text(trace_output_bytes)},
            ) as trace:
                for j in range(spans_per_trace):
                    with opik.start_as_current_span(
                        name=f"tool_call_{j}",
                        input={"prompt": _helpers.random_text(span_input_bytes)},
                        output={"completion": _helpers.random_text(span_output_bytes)},
                    ):
                        pass
                submitted_trace_ids.append(trace.id)
                last_trace_id = trace.id
            _helpers.think_time()

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )
        sample_spans: List[SpanPublic] = _helpers.verify_spans_for_trace(
            client,
            project_name=project_name,
            trace_id=last_trace_id,
            expected_count=spans_per_trace,
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)
    metrics["delivered_spans_on_sample_trace"] = len(sample_spans)
    assert len(sample_spans) >= spans_per_trace
