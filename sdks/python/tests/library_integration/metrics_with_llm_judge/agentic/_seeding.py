"""Build deterministic `TraceToolContext` inputs for judge integration tests.

The agentic judge needs:
- a `TraceModel` (trace-level input/output, error info, timing),
- a flat list of `SpanModel`s with parent links,
- a live emulator the `read` / `scan` / `search` tools can drill into.

Constructing the SpanModel objects ad-hoc per test is verbose and
error-prone. This module narrows the surface to a single helper —
`build_context(...)` — that takes inline span dicts and emits a fully-
wired `TraceToolContext` whose emulator has been seeded via the public
message API. Mirrors the unit-test `_seeding` helpers in
`tests/unit/evaluation/suite_evaluators/agentic/_seeding.py` but is
intentionally a separate module: the integration suite shouldn't import
from the unit-test tree, since the two directories have independent
collection rules and lifecycles.
"""

import datetime
from typing import Any, Dict, List, Optional

from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext
from opik.message_processing import messages
from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)


# Fixed the clock so trace/span timestamps don't drift between runs.
# Helpful for deterministic prompts and for diffing prompt snapshots
# during debugging.
_FIXED_NOW = datetime.datetime(2026, 5, 13, 12, 0, 0)


def _now() -> datetime.datetime:
    return _FIXED_NOW


def make_trace(
    *,
    trace_id: str = "t-1",
    name: str = "task",
    input: Any = None,
    output: Any = None,
    error_info: Optional[Dict[str, Any]] = None,
    duration_s: float = 1.0,
    project_name: str = "default",
) -> models.TraceModel:
    return models.TraceModel(
        id=trace_id,
        start_time=_now(),
        end_time=_now() + datetime.timedelta(seconds=duration_s),
        name=name,
        project_name=project_name,
        source="sdk",
        input=input,
        output=output,
        error_info=error_info,
    )


def make_span(
    *,
    span_id: str,
    name: str,
    input: Any = None,
    output: Any = None,
    error_info: Optional[Dict[str, Any]] = None,
    type: str = "general",
    start_offset_ms: int = 0,
    duration_ms: int = 10,
    model: Optional[str] = None,
    provider: Optional[str] = None,
) -> models.SpanModel:
    start = _now() + datetime.timedelta(milliseconds=start_offset_ms)
    return models.SpanModel(
        id=span_id,
        name=name,
        type=type,
        source="sdk",
        start_time=start,
        end_time=start + datetime.timedelta(milliseconds=duration_ms),
        input=input,
        output=output,
        error_info=error_info,
        model=model,
        provider=provider,
    )


def build_context(
    trace: models.TraceModel,
    spans: List[models.SpanModel],
    parent_by_child: Optional[Dict[str, Optional[str]]] = None,
) -> TraceToolContext:
    """Seed a fresh emulator with the given trace + spans and return a
    `TraceToolContext` ready to hand to the agentic judge.

    `parent_by_child` defaults to flat (all spans parentless). The
    emulator is created in `active=True` mode so `get_trace`,
    `spans_for_trace`, etc. all return the seeded data — which is what
    the agentic `read` / `scan` / `search` tools rely on.
    """
    parent_map: Dict[str, Optional[str]] = (
        dict(parent_by_child) if parent_by_child is not None else {}
    )
    for span in spans:
        parent_map.setdefault(span.id, None)

    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    emulator.process(
        messages.CreateTraceMessage(
            trace_id=trace.id,
            project_name=trace.project_name,
            name=trace.name,
            start_time=trace.start_time,
            end_time=trace.end_time,
            input=trace.input,
            output=trace.output,
            metadata=trace.metadata,
            tags=trace.tags,
            error_info=trace.error_info,
            thread_id=trace.thread_id,
            last_updated_at=trace.last_updated_at,
            source=trace.source,
        )
    )
    for span in spans:
        emulator.process(
            messages.CreateSpanMessage(
                span_id=span.id,
                trace_id=trace.id,
                project_name=span.project_name,
                parent_span_id=parent_map.get(span.id),
                name=span.name,
                start_time=span.start_time,
                end_time=span.end_time,
                input=span.input,
                output=span.output,
                metadata=span.metadata,
                tags=span.tags,
                type=span.type,
                usage=span.usage,
                model=span.model,
                provider=span.provider,
                error_info=span.error_info,
                total_cost=span.total_cost,
                last_updated_at=span.last_updated_at,
                source=span.source,
            )
        )

    return TraceToolContext(
        trace=trace,
        spans=spans,
        parent_by_child=parent_map,
        emulator=emulator,
    )
