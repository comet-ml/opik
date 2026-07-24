"""Per span/trace payload-size enforcement.

A span or trace whose ``input``/``output`` fields are very large - e.g. an entire
retrieval result set logged inline - inflates into a multi-GB structure on the
backend and can destabilise ingestion. This module enforces a **per-object** size
limit with a simple, predictable two-pass rule:

1. any field that on its own exceeds the limit is replaced with a truncation
   marker (the common case: one giant field, small siblings kept);
2. if the object as a whole is still over the limit, the remaining truncatable
   fields are replaced too - so the object is guaranteed to end up under the limit.

No field sorting and no per-step re-measure loop (at most one whole-object
measurement). Applied in the background message processor, right before the
create/update request is sent - after attachments have been extracted/uploaded
separately, so only content that would be sent inline is measured and truncated.
A warning is logged whenever anything is truncated.

``metadata`` is deliberately never truncated (it holds small structured fields such
as ``thread_id``/``model`` that the backend relies on) and is excluded from the
whole-object measurement, so a large ``metadata`` cannot trigger truncation of
``input``/``output``.
"""

import logging
from typing import Any, Callable, Dict, List, Optional, TypeVar

from .batching import sequence_splitter
from ..rest_api.types import span_write, trace_write

LOGGER = logging.getLogger(__name__)

# A span or trace write model - both carry the same truncatable input/output fields.
WriteT = TypeVar("WriteT", span_write.SpanWrite, trace_write.TraceWrite)

# Fields on a span/trace that can carry large user-provided payloads. Deliberately
# only `input`/`output` - `metadata` is left untouched (see the module docstring).
_TRUNCATABLE_FIELDS = ("input", "output")


def _truncation_marker(size_mb: float) -> Dict[str, Any]:
    # Compact marker left in place of an oversized field. `opik_truncated` is the
    # machine-detectable flag; `reason` is a short tag with the original size and the
    # server error codes (413/400) the field would otherwise trigger.
    return {
        "opik_truncated": True,
        "reason": f"<omitted_due_to_size_{round(size_mb)}MB_error_code_413_400>",
    }


def _log_truncation(
    kind: str, obj_id: Optional[str], max_size_mb: float, truncated_fields: List[str]
) -> None:
    LOGGER.warning(
        "%s '%s' exceeded the per-%s size limit of %s MB; truncated field(s): %s. "
        "Log large payloads as attachments to avoid truncation.",
        kind.capitalize(),
        obj_id,
        kind,
        max_size_mb,
        ", ".join(truncated_fields),
    )


def _field_sizes_mb(get_field: Callable[[str], Any]) -> Dict[str, float]:
    sizes: Dict[str, float] = {}
    for name in _TRUNCATABLE_FIELDS:
        value = get_field(name)
        if value is not None:
            sizes[name] = sequence_splitter.get_payload_size_MB(value)
    return sizes


def _plan_truncation(
    field_sizes_mb: Dict[str, float],
    max_size_mb: float,
    measure_whole: Callable[[Dict[str, Any]], float],
) -> Dict[str, Any]:
    """Decide which fields to replace with a marker so the object ends up <= the limit.

    Pass 1 truncates any field that alone exceeds the limit. Pass 2 (the hard
    per-object cap) truncates the rest only if the object is still over as a whole.
    Returns ``{field_name: marker}`` (empty if nothing needs truncating).
    """
    updates: Dict[str, Any] = {}

    # Pass 1 - fields individually over the limit (the common "one giant field").
    for name, size_mb in field_sizes_mb.items():
        if size_mb > max_size_mb:
            updates[name] = _truncation_marker(size_mb)

    # Pass 2 - hard per-object cap: if it is still over as a whole, truncate the
    # remaining truncatable fields too. One measurement, no loop.
    if measure_whole(updates) > max_size_mb:
        for name, size_mb in field_sizes_mb.items():
            if name not in updates:
                updates[name] = _truncation_marker(size_mb)

    return updates


def truncate_write_if_needed(
    obj: WriteT, max_size_mb: float, kind: str = "span"
) -> WriteT:
    """Return a copy of ``obj`` (a span or trace write) with oversized fields truncated."""
    # A non-positive limit disables the check (parity with the TS SDK). Guarding here also
    # avoids the degenerate case where a 0/negative limit would mark every field oversized.
    if max_size_mb <= 0:
        return obj
    field_sizes = _field_sizes_mb(lambda n: getattr(obj, n, None))
    if not field_sizes:
        return obj

    def measure_whole(overrides: Dict[str, Any]) -> float:
        # Exclude metadata: it is never truncated, so it must not drag the object
        # "over" the cap and trigger pointless truncation of small input/output.
        candidate = obj.model_copy(update={"metadata": None, **overrides})
        return sequence_splitter.get_payload_size_MB(candidate)

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return obj

    # The write models are frozen; model_copy(update=...) builds a new instance
    # without re-validating, so the marker dict is accepted on the JSON fields.
    result = obj.model_copy(update=updates)
    _log_truncation(kind, getattr(obj, "id", None), max_size_mb, list(updates))
    return result


def truncate_writes(
    objs: List[WriteT], max_size_mb: float, kind: str = "span"
) -> List[WriteT]:
    """Truncate every span/trace in a batch that exceeds the per-object limit."""
    return [truncate_write_if_needed(obj, max_size_mb, kind) for obj in objs]


def truncate_kwargs_if_needed(
    kwargs: Dict[str, Any], max_size_mb: float, kind: str = "span"
) -> None:
    """Truncate oversized fields on a single create/update payload dict, in place.

    Used on the non-batched create path (``use_batching=False``) and on the
    update path, so an oversized ``output``/``input`` sent via an update
    (e.g. ``span.end(output=...)`` after the create was already flushed) is capped
    the same way as on create. Applies to both spans and traces (``kind``).
    """
    # A non-positive limit disables the check (parity with the TS SDK).
    if max_size_mb <= 0:
        return
    field_sizes = _field_sizes_mb(kwargs.get)
    if not field_sizes:
        return

    def measure_whole(overrides: Dict[str, Any]) -> float:
        # Exclude metadata (never truncated) from the whole-object measurement.
        return sequence_splitter.get_payload_size_MB(
            {**kwargs, **overrides, "metadata": None}
        )

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return

    kwargs.update(updates)
    _log_truncation(kind, kwargs.get("id"), max_size_mb, list(updates))
