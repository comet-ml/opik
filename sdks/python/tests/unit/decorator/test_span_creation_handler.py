"""
Unit tests for create_span_respecting_context focusing on source handling.

Scenarios covered:
1. No context, source=None      → trace.source=None, span.source="sdk" (default)
2. No context, source set       → trace.source and span.source both set
3. Existing span, source=None   → child span inherits source from parent span
4. Existing span, source set    → child span uses explicit source (overrides parent)
5. Existing trace only, source=None  → span inherits source from trace
6. Existing trace only, source set   → span uses explicit source (overrides trace)
7. Distributed headers, source=None  → span.source="sdk" (default)
8. Distributed headers, source set   → span.source set explicitly
"""

from opik.api_objects import span as span_module, trace as trace_module
from opik.context_storage import OpikContextStorage
from opik.decorator import arguments_helpers, span_creation_handler


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_start_span_params(
    name: str = "test-span",
) -> arguments_helpers.StartSpanParameters:
    return arguments_helpers.StartSpanParameters(type="general", name=name)


def _make_storage_with_span(span_source: str = "sdk") -> OpikContextStorage:
    """Return a context storage that already has one span on the stack."""
    storage = OpikContextStorage()
    parent_span = span_module.SpanData(
        trace_id="trace-123",
        id="span-parent-id",
        source=span_source,
    )
    storage.add_span_data(parent_span)
    return storage


def _make_storage_with_trace(trace_source=None) -> OpikContextStorage:
    """Return a context storage that has a trace but no span."""
    storage = OpikContextStorage()
    t = trace_module.TraceData(id="trace-456", source=trace_source)
    storage.set_trace_data(t)
    return storage


# ---------------------------------------------------------------------------
# 1 & 2 — No existing context
# ---------------------------------------------------------------------------


def test_no_context__source_none__trace_source_sdk_span_source_sdk():
    """When no context exists and source=None, trace gets 'sdk' and span gets 'sdk'."""
    storage = OpikContextStorage()

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source=None,
    )

    assert result.trace_data is not None
    assert result.trace_data.source == "sdk"
    assert result.span_data.source == "sdk"


def test_no_context__source_set__trace_and_span_get_that_source():
    """When no context exists and source='optimization', both trace and span receive it."""
    storage = OpikContextStorage()

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source="optimization",
    )

    assert result.trace_data is not None
    assert result.trace_data.source == "optimization"
    assert result.span_data.source == "optimization"


# ---------------------------------------------------------------------------
# 3 & 4 — Existing span in context
# ---------------------------------------------------------------------------


def test_existing_span__source_none__child_inherits_parent_span_source():
    """When a parent span exists and source=None, child span inherits parent's source."""
    storage = _make_storage_with_span(span_source="optimization")

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source=None,
    )

    assert result.trace_data is None  # no new trace created
    assert result.span_data.source == "optimization"
    assert result.span_data.parent_span_id == "span-parent-id"


def test_existing_span__source_set__child_uses_explicit_source():
    """When a parent span exists and source is explicit, child uses the explicit source."""
    storage = _make_storage_with_span(span_source="sdk")

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source="optimization",
    )

    assert result.trace_data is None
    assert result.span_data.source == "optimization"


# ---------------------------------------------------------------------------
# 5 & 6 — Existing trace, no span
# ---------------------------------------------------------------------------


def test_existing_trace_no_span__source_none__span_inherits_trace_source():
    """When only a trace exists and source=None, span inherits source from the trace."""
    storage = _make_storage_with_trace(trace_source="optimization")

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source=None,
    )

    assert result.trace_data is None  # no new trace created
    assert result.span_data.parent_span_id is None
    assert result.span_data.source == "optimization"


def test_existing_trace_no_span__source_set__span_uses_explicit_source():
    """When only a trace exists and source is explicit, span uses the explicit source."""
    storage = _make_storage_with_trace(trace_source="sdk")

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=None,
        opik_context_storage=storage,
        source="optimization",
    )

    assert result.trace_data is None
    assert result.span_data.source == "optimization"


# ---------------------------------------------------------------------------
# 7 & 8 — Distributed trace headers
# ---------------------------------------------------------------------------


def test_distributed_headers__source_none__span_source_sdk():
    """With distributed headers and source=None, span defaults to 'sdk'."""
    storage = OpikContextStorage()
    headers = {
        "opik_trace_id": "remote-trace-id",
        "opik_parent_span_id": "remote-span-id",
    }

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=headers,
        opik_context_storage=storage,
        source=None,
    )

    assert result.trace_data is None
    assert result.span_data.trace_id == "remote-trace-id"
    assert result.span_data.parent_span_id == "remote-span-id"
    assert result.span_data.source == "sdk"


def test_distributed_headers__source_set__span_gets_that_source():
    """With distributed headers and an explicit source, span receives that source."""
    storage = OpikContextStorage()
    headers = {
        "opik_trace_id": "remote-trace-id",
        "opik_parent_span_id": "remote-span-id",
    }

    result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=_make_start_span_params(),
        distributed_trace_headers=headers,
        opik_context_storage=storage,
        source="optimization",
    )

    assert result.trace_data is None
    assert result.span_data.source == "optimization"
