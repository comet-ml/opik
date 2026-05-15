"""Deep-equal helpers for ``opik migrate dataset`` cascade e2e tests.

The cascade copies four kinds of entities -- experiment + experiment items
+ traces + spans -- with FK fields remapped to the destination. Counts
alone aren't enough; we also need to verify that the content (input,
output, tags, metadata, feedback scores, assertion results, span tree
shape) round-trips byte-for-byte modulo the remapped IDs.

This module provides ``compare_cascade(source_state, destination_state, rest_client)``
that recursively diff-walks both sides and raises ``AssertionError`` with
a precise message on any mismatch.

What's compared
---------------
Experiment level:
  - name, type, evaluation_method, tags, metadata
  - prompt_versions must be None on destination (epic decision: strip)

Experiment items (paired via source/dest item ordinal, which corresponds
to the source/dest dataset_item_id pairing the cascade builds):
  - assertion_results compared as a set keyed by (value, passed, reason)
  - feedback_scores compared as a set keyed by (name, value, reason, source)
  - status NOT compared -- BE computes it from assertion_results

Traces (paired via cascade's trace_id_remap):
  - name, input, output, metadata, tags, start_time, end_time,
    thread_id, error_info, ttft
  - feedback_scores compared as a set keyed by (name, value, reason, source)

Spans (tree-aware):
  - both sides sorted topologically (parent before child)
  - parent_span_id remap verified by reconstructing each side's tree and
    walking in lockstep
  - per-span: name, type, input, output, metadata, model, provider,
    tags, usage, start_time, end_time, error_info, ttft,
    total_estimated_cost
  - feedback_scores on spans compared as a set

What's NOT compared (intentional)
---------------------------------
  - any id field (id, project_id, experiment_id, dataset_id,
    dataset_version_id, dataset_item_id, trace_id, span_id,
    parent_span_id, optimization_id) -- they all change during cascade
  - audit fields (created_at, last_updated_at, created_by, last_updated_by)
  - BE-computed aggregates on traces/items (trace_count,
    total_estimated_cost, duration, usage, span_count, llm_span_count,
    has_tool_spans, providers, span_feedback_scores)
  - ``project_name`` on experiment metadata (Slice 3 stamps it on the
    destination as part of recreate_experiment; differs intentionally)
  - ``prompt_versions`` (stripped on destination per epic decision)
  - ``optimization_id`` (stripped on destination -- Slice 4's territory)

Trace ``input`` / ``output`` JSON that embeds source-side IDs (e.g.
``{'item': '<src-dataset-item-id>'}``) round-trips verbatim. The cascade
deliberately does not recursively remap arbitrary JSON content. Tests
that seed embedded IDs in trace I/O and care about post-migration
freshness need their own narrower assertion; this module compares the
JSON shape verbatim because that IS the cascade's contract.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from opik.rest_api import OpikApi


# ---------------------------------------------------------------------------
# Top-level entrypoint
# ---------------------------------------------------------------------------


def compare_cascade(
    *,
    rest_client: OpikApi,
    source_experiment: Any,
    destination_experiment: Any,
    source_item_ids: List[str],
    destination_item_ids: List[str],
    source_trace_ids: List[str],
    destination_trace_ids: List[str],
    source_items_compare: List[Any],
    destination_items_compare: List[Any],
) -> None:
    """Deep-equal the experiment + items + traces + spans between source and
    destination, modulo remapped IDs.

    Raises ``AssertionError`` with a focused message on any divergence.

    The trace pairing is positional: ``source_trace_ids[i]`` must correspond
    to ``destination_trace_ids[i]`` (callers maintain this ordering when
    they seed + read). Same for items.
    """
    _compare_experiment(source_experiment, destination_experiment)

    if len(source_items_compare) != len(destination_items_compare):
        raise AssertionError(
            f"item count diverged: source={len(source_items_compare)}, "
            f"destination={len(destination_items_compare)}"
        )
    if len(source_trace_ids) != len(destination_trace_ids):
        raise AssertionError(
            f"trace count diverged: source={len(source_trace_ids)}, "
            f"destination={len(destination_trace_ids)}"
        )

    # Items are typically returned in BE-imposed order (e.g. by created_at
    # desc). Pair by dataset_item_id round-trip: source item with source
    # dataset_item_id S maps to destination item with destination
    # dataset_item_id D where D = item_id_remap[S]. The callers pass the
    # already-paired ordered lists, so positional zip works.
    for src_item, dst_item in zip(source_items_compare, destination_items_compare):
        _compare_experiment_item(src_item, dst_item)

    # Traces compared in pairs.
    for src_tid, dst_tid in zip(source_trace_ids, destination_trace_ids):
        src_trace = rest_client.traces.get_trace_by_id(id=src_tid)
        dst_trace = rest_client.traces.get_trace_by_id(id=dst_tid)
        _compare_trace(src_trace, dst_trace)

        # Spans for this trace. ``project_id`` lives on the trace's read
        # shape and scopes the spans query correctly without needing the
        # caller to plumb project_name everywhere.
        src_spans = _fetch_spans_for_trace(
            rest_client, trace_id=src_tid, project_id=src_trace.project_id
        )
        dst_spans = _fetch_spans_for_trace(
            rest_client, trace_id=dst_tid, project_id=dst_trace.project_id
        )
        _compare_span_trees(src_spans, dst_spans)


# ---------------------------------------------------------------------------
# Experiment-level
# ---------------------------------------------------------------------------


def _compare_experiment(src: Any, dst: Any) -> None:
    if src.name != dst.name:
        raise AssertionError(
            f"experiment.name diverged: source={src.name!r}, destination={dst.name!r}"
        )
    if src.type != dst.type:
        raise AssertionError(
            f"experiment.type diverged: source={src.type!r}, destination={dst.type!r}"
        )
    if src.evaluation_method != dst.evaluation_method:
        raise AssertionError(
            f"experiment.evaluation_method diverged: source={src.evaluation_method!r}, "
            f"destination={dst.evaluation_method!r}"
        )
    if (src.tags or None) != (dst.tags or None):
        raise AssertionError(
            f"experiment.tags diverged: source={src.tags!r}, destination={dst.tags!r}"
        )

    # Metadata: compare modulo Slice 3's injections.
    # - ``project_name`` is stamped on the destination by recreate_experiment
    #   (kept as a forward-import hint); on source it depends on how the
    #   experiment was created. Strip from both for comparison.
    # - ``prompt_versions`` is stripped on the destination by design.
    src_meta = dict(src.metadata or {})
    dst_meta = dict(dst.metadata or {})
    src_meta.pop("project_name", None)
    dst_meta.pop("project_name", None)
    src_meta.pop("prompt_versions", None)
    dst_meta.pop("prompt_versions", None)
    if src_meta != dst_meta:
        raise AssertionError(
            f"experiment.metadata diverged (after stripping project_name + "
            f"prompt_versions): source={src_meta!r}, destination={dst_meta!r}"
        )

    # Per epic decision, destination must have prompt_versions stripped.
    if dst.prompt_versions:
        raise AssertionError(
            f"experiment.prompt_versions should be stripped on destination "
            f"(epic decision); got {dst.prompt_versions!r}"
        )

    # Per epic decision, destination must have optimization_id stripped.
    if dst.optimization_id:
        raise AssertionError(
            f"experiment.optimization_id should be stripped on destination "
            f"(Slice 4 cascades the optimization entity); "
            f"got {dst.optimization_id!r}"
        )


# ---------------------------------------------------------------------------
# Experiment item (Compare view)
# ---------------------------------------------------------------------------


def _compare_experiment_item(src: Any, dst: Any) -> None:
    src_ars = _normalize_assertions(src.assertion_results)
    dst_ars = _normalize_assertions(dst.assertion_results)
    if src_ars != dst_ars:
        raise AssertionError(
            f"experiment item assertion_results diverged: "
            f"source={src_ars}, destination={dst_ars}"
        )

    src_fs = _normalize_feedback_scores(src.feedback_scores)
    dst_fs = _normalize_feedback_scores(dst.feedback_scores)
    if src_fs != dst_fs:
        raise AssertionError(
            f"experiment item feedback_scores diverged: "
            f"source={src_fs}, destination={dst_fs}"
        )


# ---------------------------------------------------------------------------
# Trace
# ---------------------------------------------------------------------------


_TRACE_DIRECT_FIELDS: Tuple[str, ...] = (
    "name",
    "input",
    "output",
    "metadata",
    "tags",
    "thread_id",
    "ttft",
)


def _compare_trace(src: Any, dst: Any) -> None:
    for field in _TRACE_DIRECT_FIELDS:
        s = getattr(src, field, None)
        d = getattr(dst, field, None)
        if (s or None) != (d or None):
            raise AssertionError(
                f"trace.{field} diverged: source={s!r}, destination={d!r}"
            )

    # ``error_info`` model_dump for content comparison; the read shape is
    # ErrorInfoPublic on both sides so dicts should be equal.
    s_err = _safe_dump(src.error_info)
    d_err = _safe_dump(dst.error_info)
    if s_err != d_err:
        raise AssertionError(
            f"trace.error_info diverged: source={s_err}, destination={d_err}"
        )

    # start_time / end_time round-trip as-is; the cascade copies them
    # verbatim from the source trace. ms precision differences would
    # surface here.
    if src.start_time != dst.start_time:
        raise AssertionError(
            f"trace.start_time diverged: source={src.start_time}, "
            f"destination={dst.start_time}"
        )
    if (src.end_time or None) != (dst.end_time or None):
        raise AssertionError(
            f"trace.end_time diverged: source={src.end_time}, "
            f"destination={dst.end_time}"
        )

    # Feedback scores compared as a set keyed by name+value+reason+source.
    src_fs = _normalize_feedback_scores(src.feedback_scores)
    dst_fs = _normalize_feedback_scores(dst.feedback_scores)
    if src_fs != dst_fs:
        raise AssertionError(
            f"trace.feedback_scores diverged: source={src_fs}, destination={dst_fs}"
        )


# ---------------------------------------------------------------------------
# Span tree
# ---------------------------------------------------------------------------


_SPAN_DIRECT_FIELDS: Tuple[str, ...] = (
    "name",
    "type",
    "input",
    "output",
    "metadata",
    "model",
    "provider",
    "tags",
    "usage",
    "total_estimated_cost",
    "ttft",
)


def _compare_span_trees(src_spans: List[Any], dst_spans: List[Any]) -> None:
    """Walk both span trees in parallel, comparing per-node fields and
    verifying parent_span_id remap (children's new parent must be the
    remapped new root, etc.).

    Pairs spans across the two sides by tree position: both lists are
    sorted topologically (parents first) and within a parent's children
    by (name, start_time). The cascade preserves source order via
    ``sort_spans_topologically`` so a stable sort makes this
    deterministic.
    """
    if len(src_spans) != len(dst_spans):
        raise AssertionError(
            f"span count diverged: source={len(src_spans)}, "
            f"destination={len(dst_spans)}"
        )

    src_sorted = _topo_sort_for_compare(src_spans)
    dst_sorted = _topo_sort_for_compare(dst_spans)

    src_to_dst_span_id: Dict[Optional[str], Optional[str]] = {None: None}
    for src_span, dst_span in zip(src_sorted, dst_sorted):
        src_to_dst_span_id[src_span.id] = dst_span.id

        for field in _SPAN_DIRECT_FIELDS:
            s = getattr(src_span, field, None)
            d = getattr(dst_span, field, None)
            if (s or None) != (d or None):
                raise AssertionError(
                    f"span.{field} diverged (source span id={src_span.id!r}, "
                    f"dest span id={dst_span.id!r}): source={s!r}, destination={d!r}"
                )

        # Timestamps verbatim.
        if src_span.start_time != dst_span.start_time:
            raise AssertionError(
                f"span.start_time diverged (source span id={src_span.id!r}): "
                f"source={src_span.start_time}, destination={dst_span.start_time}"
            )
        if (src_span.end_time or None) != (dst_span.end_time or None):
            raise AssertionError(
                f"span.end_time diverged (source span id={src_span.id!r}): "
                f"source={src_span.end_time}, destination={dst_span.end_time}"
            )

        # Error info.
        s_err = _safe_dump(getattr(src_span, "error_info", None))
        d_err = _safe_dump(getattr(dst_span, "error_info", None))
        if s_err != d_err:
            raise AssertionError(
                f"span.error_info diverged (source span id={src_span.id!r}): "
                f"source={s_err}, destination={d_err}"
            )

        # Feedback scores compared as a set.
        s_fs = _normalize_feedback_scores(getattr(src_span, "feedback_scores", None))
        d_fs = _normalize_feedback_scores(getattr(dst_span, "feedback_scores", None))
        if s_fs != d_fs:
            raise AssertionError(
                f"span.feedback_scores diverged (source span id={src_span.id!r}): "
                f"source={s_fs}, destination={d_fs}"
            )

        # parent_span_id remap correctness: the destination span's
        # parent_span_id must be the destination id of the source span's
        # parent (or None for root).
        expected_dst_parent = src_to_dst_span_id.get(src_span.parent_span_id)
        if dst_span.parent_span_id != expected_dst_parent:
            raise AssertionError(
                f"span.parent_span_id remap incorrect "
                f"(source span id={src_span.id!r}, source parent={src_span.parent_span_id!r}): "
                f"expected destination parent={expected_dst_parent!r}, "
                f"got destination parent={dst_span.parent_span_id!r}"
            )


# ---------------------------------------------------------------------------
# Normalisation helpers
# ---------------------------------------------------------------------------


def _normalize_assertions(items: Optional[List[Any]]) -> List[Tuple[Any, Any, Any]]:
    """Set-equality-friendly tuples keyed by the AssertionResult identity:
    (value, passed, reason). Sorted so list-equality also works."""
    if not items:
        return []
    return sorted(
        ((a.value, a.passed, a.reason) for a in items),
        key=lambda t: (str(t[0]), bool(t[1]), str(t[2] or "")),
    )


def _normalize_feedback_scores(
    items: Optional[List[Any]],
) -> List[Tuple[Any, ...]]:
    """Set-equality-friendly tuples keyed by (name, value, reason, source).
    Source vs destination scores might come back in different orders; the
    sort makes the comparison stable."""
    if not items:
        return []
    return sorted(
        (
            (
                getattr(f, "name", None),
                getattr(f, "value", None),
                getattr(f, "category_name", None),
                getattr(f, "reason", None),
                getattr(f, "source", None),
            )
            for f in items
        ),
        key=lambda t: tuple(str(x) for x in t),
    )


def _safe_dump(obj: Any) -> Optional[Dict[str, Any]]:
    if obj is None:
        return None
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if isinstance(obj, dict):
        return obj
    return {"_raw": str(obj)}


def _topo_sort_for_compare(spans: List[Any]) -> List[Any]:
    """Topological sort that's also stable on (name, start_time).

    The cascade re-emits spans in source topological order. The BE may
    return them in a different ordering on read; this helper produces a
    deterministic order on both sides so paired comparison works.
    """
    by_id: Dict[Optional[str], Any] = {s.id: s for s in spans}
    children: Dict[Optional[str], List[Any]] = {None: []}
    for s in spans:
        children.setdefault(s.parent_span_id, []).append(s)
    # Sort each parent's children deterministically.
    for parent_id, kids in children.items():
        kids.sort(key=lambda s: (s.name or "", str(s.start_time)))

    out: List[Any] = []

    def _walk(parent_id: Optional[str]) -> None:
        for s in children.get(parent_id, []):
            out.append(s)
            _walk(s.id)

    _walk(None)
    # Defensive: catch orphans (spans whose parent isn't in the same tree).
    if len(out) != len(spans):
        # Append orphans at the end in deterministic order.
        seen = {s.id for s in out}
        orphans = [s for s in spans if s.id not in seen]
        orphans.sort(key=lambda s: (s.name or "", str(s.start_time)))
        out.extend(orphans)
    _ = by_id  # by_id retained for clarity / potential future use
    return out


def _fetch_spans_for_trace(
    rest_client: OpikApi, *, trace_id: str, project_id: Optional[str]
) -> List[Any]:
    """Pull all spans for one trace from the BE.

    Scopes by ``project_id`` (off the trace's read shape), required by
    the BE.
    """
    collected: List[Any] = []
    page = 1
    while True:
        resp = rest_client.spans.get_spans_by_project(
            project_id=project_id,
            trace_id=trace_id,
            page=page,
            size=200,
        )
        page_content = resp.content or []
        collected.extend(page_content)
        if len(page_content) < 200:
            break
        page += 1
    return collected
