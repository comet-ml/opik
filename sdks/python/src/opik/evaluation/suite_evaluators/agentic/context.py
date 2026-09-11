import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional, Tuple

from opik.message_processing.emulation import (
    emulator_message_processor,
    models,
)

from . import entity_ref
from .compression import trace_compressor

LOGGER = logging.getLogger(__name__)


def _isoformat(value: Optional[datetime.datetime]) -> Optional[str]:
    if value is None:
        return None
    return value.isoformat()


def _serialize_span(
    span: models.SpanModel, parent_span_id: Optional[str] = None
) -> Dict[str, Any]:
    """Serialize a SpanModel to a JSON-shaped dict.

    Child spans are intentionally NOT inlined here; the span tree is rendered
    separately via SpanTreeSerializer (used to render the inlined overview) so that the cache
    holds one entry per span and `read(type=span, id=...)` returns just that
    span. Children references would otherwise produce duplicate data and
    inconsistent caching behavior.

    `parent_span_id` is sourced from the emulator's span-to-parent map at
    cache time so the SKELETON tier of `read(type=trace, ...)` can rebuild
    the nested span tree without needing the live emulator.
    """
    return {
        "id": span.id,
        "name": span.name,
        "type": span.type,
        "parent_span_id": parent_span_id,
        "project_name": span.project_name,
        "start_time": _isoformat(span.start_time),
        "end_time": _isoformat(span.end_time),
        "input": span.input,
        "output": span.output,
        "metadata": span.metadata,
        "tags": span.tags,
        "usage": span.usage,
        "model": span.model,
        "provider": span.provider,
        "error_info": span.error_info,
        "total_cost": span.total_cost,
    }


def _serialize_trace(
    trace: models.TraceModel,
) -> Dict[str, Any]:
    return {
        "id": trace.id,
        "name": trace.name,
        "project_name": trace.project_name,
        "thread_id": trace.thread_id,
        "start_time": _isoformat(trace.start_time),
        "end_time": _isoformat(trace.end_time),
        "input": trace.input,
        "output": trace.output,
        "metadata": trace.metadata,
        "tags": trace.tags,
        "error_info": trace.error_info,
    }


@dataclasses.dataclass
class TraceToolContext:
    """Per-evaluation cache plumbed into agentic-judge tools.

    Pre-seeds the active trace and its spans on construction so `scan` /
    `search` (later phases) see them without a preceding `read`. Backed by
    the LocalEmulatorMessageProcessor; no network fetches.
    """

    trace: models.TraceModel
    spans: List[models.SpanModel]
    parent_by_child: Dict[str, Optional[str]]
    emulator: emulator_message_processor.EmulatorMessageProcessor
    _fetched: Dict[entity_ref.EntityRef, Dict[str, Any]] = dataclasses.field(
        default_factory=dict
    )

    def __post_init__(self) -> None:
        # Trace cache holds the COMPOSITE `{"trace": ..., "spans": [...]}`
        # so `scan(type=trace, expression='.spans[0].input')` works without
        # a preceding `read`. Matches backend cache semantics
        # (see TraceCompressor.buildFullJson on the Java side).
        span_dicts = [
            _serialize_span(span, self.parent_by_child.get(span.id))
            for span in self.spans
        ]
        self._fetched[
            entity_ref.EntityRef(entity_ref.EntityType.TRACE, self.trace.id)
        ] = trace_compressor.build_full_json(_serialize_trace(self.trace), span_dicts)
        for span, span_dict in zip(self.spans, span_dicts):
            self._fetched[entity_ref.EntityRef(entity_ref.EntityType.SPAN, span.id)] = (
                span_dict
            )

    def get_cached(self, ref: entity_ref.EntityRef) -> Optional[Dict[str, Any]]:
        return self._fetched.get(ref)

    def cache(self, ref: entity_ref.EntityRef, payload: Dict[str, Any]) -> None:
        self._fetched[ref] = payload

    def lookup_from_emulator(
        self, ref: entity_ref.EntityRef
    ) -> Optional[Dict[str, Any]]:
        """Best-effort resolution against the local emulator for non-pre-seeded entities.

        Traces resolve to the composite `{"trace": ..., "spans": [...]}`
        shape used by the pre-seeded cache. Spans resolve to a flat span
        dict. Only TRACE and SPAN are in scope for v1.
        """
        if ref.type == entity_ref.EntityType.TRACE:
            trace = self.emulator.get_trace(ref.id)
            if trace is None:
                return None
            spans = self.emulator.spans_for_trace(ref.id)
            parent_map = self.emulator.parent_span_ids_for_trace(ref.id)
            return trace_compressor.build_full_json(
                _serialize_trace(trace),
                [_serialize_span(span, parent_map.get(span.id)) for span in spans],
            )
        if ref.type == entity_ref.EntityType.SPAN:
            span = self.emulator.get_span(ref.id)
            if span is None:
                return None
            trace_id = self.emulator.trace_id_for_span(span.id)
            parent_id = (
                self.emulator.parent_span_ids_for_trace(trace_id).get(span.id)
                if trace_id is not None
                else None
            )
            return _serialize_span(span, parent_id)
        return None


# Namespaced sentinel tag that the eval engine attaches (via
# `@opik.track(tags=[INTERNAL_SPAN_TAG])`) to every span it emits as part
# of its own scoring/metrics plumbing. The agentic judge has no business
# seeing those spans:
#
# - `metrics_calculation` (and its `task_span_` sibling) is emitted by
#   `EvaluationEngine` to wrap each test case's scoring phase. Its
#   `input` carries the full `TestCase` envelope including the entire
#   `LLMJudgeConfig` (model, messages, schema, assertion descriptions).
#   For an LLM-judge evaluation, that means **the assertion text —
#   including any literal tokens the assertion mentions — gets echoed
#   back into the trace the judge is asked to evaluate.** Concretely:
#   an assertion of the form "the agent processed a payload containing
#   'MARKER-xyz'" makes `MARKER-xyz` appear in the scoring span's
#   `input` alongside its legitimate location in the agent's span. The
#   judge then can't tell whether it observed real agent activity or
#   just the assertion echoing itself, and burns tool calls
#   disambiguating. Filtering the tagged subtree removes the leak at
#   the source.
#
# Why a namespaced tag instead of a name-based filter: span names are
# user-settable (`@opik.track(name=...)`), so matching on
# `name == "metrics_calculation"` silently drops legitimate user spans
# that happen to use that name. The `__opik_eval_internal__` tag is set
# only by the engine, so the filter targets eval-engine plumbing
# exactly. Descendants of tagged spans are eval-engine internals too
# (other scorers, model wrappers, downstream metric calls), so the full
# subtree gets dropped — see `_filter_internal_spans`.
INTERNAL_SPAN_TAG = "__opik_eval_internal__"


def _filter_internal_spans(
    spans: List[models.SpanModel],
    parent_by_child: Dict[str, Optional[str]],
) -> Tuple[List[models.SpanModel], Dict[str, Optional[str]]]:
    """Drop opik-internal spans (and their descendants) from the agentic view.

    Seeds the "internal" set from spans whose `tags` contain
    `INTERNAL_SPAN_TAG`, then sweeps the parent map until closure so any
    span whose ancestor chain reaches a tagged root is also dropped. The
    whole subtree under an eval-engine scoring span is plumbing (other
    scorers, model wrappers) and shouldn't be visible to the judge
    either.

    Returns a fresh `(spans, parent_by_child)` pair so callers don't
    mutate the emulator's caches. No-ops (returns the originals
    unchanged) when no tagged spans are present, which is the common
    case for non-suite agentic-judge invocations.
    """
    internal_ids: set = {
        span.id for span in spans if span.tags and INTERNAL_SPAN_TAG in span.tags
    }
    if not internal_ids:
        return spans, parent_by_child

    # Sweep descendants. Each pass adds spans whose parent is already
    # marked internal; loop until the set stops growing. Span trees are
    # shallow, so a few iterations suffice.
    changed = True
    while changed:
        changed = False
        for span in spans:
            if span.id in internal_ids:
                continue
            parent = parent_by_child.get(span.id)
            if parent in internal_ids:
                internal_ids.add(span.id)
                changed = True

    kept_spans = [span for span in spans if span.id not in internal_ids]
    kept_parent_by_child = {
        span_id: parent
        for span_id, parent in parent_by_child.items()
        if span_id not in internal_ids
    }
    return kept_spans, kept_parent_by_child


def build_trace_tool_context(
    trace_id: str,
    emulator: emulator_message_processor.EmulatorMessageProcessor,
) -> Optional[TraceToolContext]:
    """Construct a context for `trace_id` if the emulator has the trace.

    Returns None when the trace isn't in the emulator cache — caller falls
    back to the one-shot judge path. Used by re-scoring code paths
    where the trace is already in the emulator from an earlier run.
    For the live LLM-task flow, prefer `build_trace_tool_context_from_trace_data`
    — see the docstring there for why.
    """
    trace = emulator.get_trace(trace_id)
    if trace is None:
        LOGGER.debug(
            "TraceToolContext: trace %s not in emulator cache; agentic path skipped",
            trace_id,
        )
        return None

    spans = emulator.spans_for_trace(trace_id)
    parent_by_child = emulator.parent_span_ids_for_trace(trace_id)
    spans, parent_by_child = _filter_internal_spans(spans, parent_by_child)
    return TraceToolContext(
        trace=trace,
        spans=spans,
        parent_by_child=parent_by_child,
        emulator=emulator,
    )


# Forward alias for the helper signature: callers pass an
# `opik.api_objects.trace.TraceData` here. We don't import the concrete
# type to keep this module free of api_objects imports (the dependency
# direction is engine → agentic, not the reverse).
TraceDataLike = Any


def build_trace_tool_context_from_trace_data(
    trace_data: TraceDataLike,
    emulator: emulator_message_processor.EmulatorMessageProcessor,
) -> TraceToolContext:
    """Construct a context from an in-memory `TraceData` plus emulator-derived spans.

    Why this exists: during a live LLM-task evaluation, the engine
    creates `TraceData` at the top of the per-item flow but the
    corresponding `CreateTraceMessage` is only emitted in the
    `evaluate_llm_task_context.__exit__` (see
    `evaluation/engine/helpers.py`). Scoring runs **inside** that
    context manager, so when `_compute_test_result_for_test_case` asks
    the engine to build an agentic context, the trace isn't in the
    emulator yet — the lookup-based `build_trace_tool_context` would
    return None and the agentic path would silently bypass.

    Spans, by contrast, are emitted inline by `@opik.track` as each
    decorated function enters/exits, so they DO reach the emulator
    before scoring. This helper synthesizes a `TraceModel` from the
    in-memory `TraceData` and pulls span data from the emulator the
    same way the lookup-based path does — single coherent context, no
    dependency on trace-message ordering.

    Caller must ensure the emulator has had a chance to process span
    messages (call `emulator.trace_trees` once before this if needed)
    so `spans_for_trace` returns the complete set.
    """
    synthesized_trace = _trace_model_from_trace_data(trace_data)
    spans = emulator.spans_for_trace(trace_data.id)
    parent_by_child = emulator.parent_span_ids_for_trace(trace_data.id)
    spans, parent_by_child = _filter_internal_spans(spans, parent_by_child)
    LOGGER.debug(
        "[diag] build_trace_tool_context_from_trace_data trace_id=%s "
        "trace.output=%r span_outputs=%s",
        trace_data.id,
        synthesized_trace.output,
        [(s.id, s.output) for s in spans],
    )
    return TraceToolContext(
        trace=synthesized_trace,
        spans=spans,
        parent_by_child=parent_by_child,
        emulator=emulator,
    )


def _trace_model_from_trace_data(trace_data: TraceDataLike) -> models.TraceModel:
    """Project a `TraceData` (from `opik.api_objects.trace`) onto the
    `TraceModel` shape the agentic tools consume.

    The two types carry the same fields under the same names — we copy
    only the fields the agentic path serializes (see `_serialize_trace`
    above). Anything not in the projection (feedback_scores,
    attachments, last_updated_at) defaults to the TraceModel's own
    factory values, which match what an emulator-stored trace would
    look like at this point in the flow anyway.
    """
    return models.TraceModel(
        id=trace_data.id,
        start_time=trace_data.start_time,
        name=trace_data.name,
        project_name=trace_data.project_name,
        source=trace_data.source,
        input=trace_data.input,
        output=trace_data.output,
        tags=trace_data.tags,
        metadata=trace_data.metadata,
        end_time=trace_data.end_time,
        error_info=trace_data.error_info,
        thread_id=trace_data.thread_id,
    )
