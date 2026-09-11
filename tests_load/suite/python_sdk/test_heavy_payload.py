"""Heavy payload scenarios."""

from typing import List, Set

import opik
from opik.rest_api.types.span_public import SpanPublic

from . import _helpers
from ._helpers import KB, MB, Metrics


def test_traces_with_one_megabyte_payload(
    metrics: Metrics, load_scale: float
) -> None:
    """Low trace count, very large per-trace input/output, via ``@opik.track``.

    Each call to ``handle_request`` produces a trace whose ``input``
    contains ~1 MB of random text (the function argument) and whose
    ``output`` is another ~1 MB (the return value). The decorator
    captures both automatically — the same shape as wrapping an LLM call
    that takes a long prompt and returns a long completion.

    Volume: 500 traces × (1 MB in + 1 MB out) ≈ 1 GB of payload.

    Verifies every submitted trace id lands with required fields set.
    """
    trace_count: int = int(500 * load_scale)
    trace_input_bytes: int = 1 * MB
    trace_output_bytes: int = 1 * MB
    project_name: str = _helpers.unique_project_name("heavy-trace-payload")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["trace_input_bytes"] = trace_input_bytes
    metrics["trace_output_bytes"] = trace_output_bytes

    submitted_trace_ids: List[str] = []

    @opik.track(project_name=project_name)
    def handle_request(prompt: str) -> str:
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return _helpers.random_text(trace_output_bytes)

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


def test_spans_with_heavy_payload(metrics: Metrics, load_scale: float) -> None:
    """Moderate fan-out of heavy spans under small trace shells, via context managers.

    Uses ``start_as_current_trace`` / ``start_as_current_span`` so each
    span explicitly carries ~500 KB in ``input`` and ``output``. The outer
    trace itself stays small. Stresses span batching for large payloads
    where the parent trace is light.

    Volume: 200 traces × 5 spans × (500 KB in + 500 KB out) ≈ 1 GB of
    span payload total. Trace shells are ~1 KB each.

    Verifies every submitted trace id lands with required fields set, and
    that the last trace's 5 spans are all visible and well-formed.
    """
    trace_count: int = int(200 * load_scale)
    spans_per_trace: int = 5
    trace_input_bytes: int = 1 * KB
    trace_output_bytes: int = 1 * KB
    span_input_bytes: int = 500 * KB
    span_output_bytes: int = 500 * KB
    project_name: str = _helpers.unique_project_name("heavy-span-payload")

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
                        name=f"heavy_tool_call_{j}",
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
