"""Per-span payload-size enforcement.

A span whose fields (``input``/``output``/``metadata``) are very large - e.g. an
entire retrieval result set logged inline - inflates into a multi-GB structure on
the backend and can destabilise ingestion. This module enforces a **per-span**
size limit with a simple, predictable two-pass rule:

1. any field that on its own exceeds the limit is replaced with a truncation
   marker (the common case: one giant field, small siblings kept);
2. if the span as a whole is still over the limit, the remaining truncatable
   fields are replaced too - so the span is guaranteed to end up under the limit.

No field sorting and no per-step re-measure loop (at most one whole-span
measurement). Applied in the background message processor, right before the
create/update request is sent - after attachments have been extracted/uploaded
separately, so only content that would be sent inline is measured and truncated.
A warning is logged whenever anything is truncated.
"""

import logging
from typing import Any, Callable, Dict, List, Optional

from .batching import sequence_splitter
from ..rest_api.types import span_write

LOGGER = logging.getLogger(__name__)

# Fields on a span that can carry large user-provided payloads.
_TRUNCATABLE_FIELDS = ("input", "output", "metadata")

_DOCS_URL = "https://www.comet.com/docs/opik/tracing/advanced/log_multimodal_traces"


def _truncation_marker(
    field_name: str, original_size_bytes: int, max_size_mb: float
) -> Dict[str, Any]:
    return {
        "opik_truncated": True,
        "reason": (
            f"'{field_name}' was truncated by the Opik SDK because the span exceeded "
            f"the configured per-span size limit of {max_size_mb} MB. Log large "
            f"payloads as attachments instead ({_DOCS_URL})."
        ),
        "original_size_bytes": original_size_bytes,
    }


def _log_truncation(
    span_id: Optional[str], max_size_mb: float, truncated_fields: List[str]
) -> None:
    LOGGER.warning(
        "Span '%s' exceeded the per-span size limit of %s MB; truncated field(s): %s. "
        "Log large payloads as attachments to avoid truncation: %s",
        span_id,
        max_size_mb,
        ", ".join(truncated_fields),
        _DOCS_URL,
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
    """Decide which fields to replace with a marker so the span ends up <= the limit.

    Pass 1 truncates any field that alone exceeds the limit. Pass 2 (the hard
    per-span cap) truncates the rest only if the span is still over as a whole.
    Returns ``{field_name: marker}`` (empty if nothing needs truncating).
    """
    updates: Dict[str, Any] = {}

    # Pass 1 - fields individually over the limit (the common "one giant field").
    for name, size_mb in field_sizes_mb.items():
        if size_mb > max_size_mb:
            updates[name] = _truncation_marker(
                name, int(size_mb * 1024 * 1024), max_size_mb
            )

    # Pass 2 - hard per-span cap: if the span is still over as a whole, truncate
    # the remaining truncatable fields too. One measurement, no loop.
    if measure_whole(updates) > max_size_mb:
        for name, size_mb in field_sizes_mb.items():
            if name not in updates:
                updates[name] = _truncation_marker(
                    name, int(size_mb * 1024 * 1024), max_size_mb
                )

    return updates


def truncate_span_write_if_needed(
    span: span_write.SpanWrite, max_size_mb: float
) -> span_write.SpanWrite:
    """Return a copy of ``span`` with oversized fields truncated, or ``span`` unchanged."""
    field_sizes = _field_sizes_mb(lambda n: getattr(span, n, None))
    if not field_sizes:
        return span

    def measure_whole(overrides: Dict[str, Any]) -> float:
        candidate = span.model_copy(update=overrides) if overrides else span
        return sequence_splitter.get_payload_size_MB(candidate)

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return span

    # SpanWrite is frozen; model_copy(update=...) builds a new instance without
    # re-validating, so the marker dict is accepted on the JSON fields.
    result = span.model_copy(update=updates)
    _log_truncation(getattr(span, "id", None), max_size_mb, list(updates))
    return result


def truncate_span_writes(
    spans: List[span_write.SpanWrite], max_size_mb: float
) -> List[span_write.SpanWrite]:
    """Truncate every span in a batch that exceeds the per-span limit."""
    return [truncate_span_write_if_needed(span, max_size_mb) for span in spans]


def truncate_span_kwargs_if_needed(
    span_kwargs: Dict[str, Any], max_size_mb: float
) -> None:
    """Truncate oversized fields on a single-span create/update payload, in place.

    Used on the non-batched create path (``use_batching=False``) and on the
    span-update path, so an oversized ``output``/``input``/``metadata`` sent via
    ``update_span`` (e.g. ``span.end(output=...)`` after the create was already
    flushed) is capped the same way as on create.
    """
    field_sizes = _field_sizes_mb(span_kwargs.get)
    if not field_sizes:
        return

    def measure_whole(overrides: Dict[str, Any]) -> float:
        return sequence_splitter.get_payload_size_MB({**span_kwargs, **overrides})

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return

    span_kwargs.update(updates)
    _log_truncation(span_kwargs.get("id"), max_size_mb, list(updates))
