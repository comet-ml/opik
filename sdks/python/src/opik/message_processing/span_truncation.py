"""Per-span payload-size enforcement.

A single span whose ``input``/``output``/``metadata`` is very large (e.g. an
entire retrieval result set logged inline) inflates into a multi-GB structure on
the backend and can destabilise ingestion. This module enforces a configurable
per-span size limit: if a span exceeds it, the largest offending field(s) are
replaced with a small truncation marker and a warning is logged.

It is applied in the background message processor, right before the create
request is sent to the backend, i.e. *after* attachments have already been
extracted from the payload and uploaded separately - so only the content that
would actually be sent inline is measured and truncated.
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
    span_id: Optional[str],
    original_size_mb: float,
    max_size_mb: float,
    truncated_fields: List[str],
    still_oversized: bool,
) -> None:
    LOGGER.warning(
        "Span '%s' (%.1f MB) exceeded the per-span size limit of %s MB; "
        "truncated field(s): %s. Log large payloads as attachments to avoid "
        "truncation: %s",
        span_id,
        original_size_mb,
        max_size_mb,
        ", ".join(truncated_fields),
        _DOCS_URL,
    )
    if still_oversized:
        LOGGER.warning(
            "Span '%s' still exceeds the %s MB limit after truncating every "
            "truncatable field - the remaining size comes from other fields. It "
            "may be rejected by the backend.",
            span_id,
            max_size_mb,
        )


def _plan_truncation(
    measure_with_overrides: Callable[[Dict[str, Any]], float],
    field_sizes_mb: Dict[str, float],
    max_size_mb: float,
) -> Dict[str, Any]:
    """Decide which fields to replace with a marker.

    Replaces the largest fields first, **re-measuring the real serialized size**
    (including non-truncatable fields and overhead) after each replacement, and
    stops as soon as the span fits. Returns ``{field_name: marker}`` (empty if
    nothing needs truncating).
    """
    updates: Dict[str, Any] = {}
    for name in sorted(field_sizes_mb, key=lambda n: field_sizes_mb[n], reverse=True):
        if measure_with_overrides(updates) <= max_size_mb:
            break
        original_size_bytes = int(field_sizes_mb[name] * 1024 * 1024)
        updates[name] = _truncation_marker(name, original_size_bytes, max_size_mb)
    return updates


def _field_sizes_mb(get_field: Callable[[str], Any]) -> Dict[str, float]:
    return {
        name: sequence_splitter.get_payload_size_MB(get_field(name))
        for name in _TRUNCATABLE_FIELDS
        if get_field(name) is not None
    }


def truncate_span_write_if_needed(
    span: span_write.SpanWrite, max_size_mb: float
) -> span_write.SpanWrite:
    """Return a copy of ``span`` with oversized fields truncated, or ``span`` unchanged."""
    original_size_mb = sequence_splitter.get_payload_size_MB(span)
    if original_size_mb <= max_size_mb:
        return span

    def measure(overrides: Dict[str, Any]) -> float:
        candidate = span.model_copy(update=overrides) if overrides else span
        return sequence_splitter.get_payload_size_MB(candidate)

    updates = _plan_truncation(
        measure, _field_sizes_mb(lambda n: getattr(span, n, None)), max_size_mb
    )
    if not updates:
        return span

    # SpanWrite is frozen; model_copy(update=...) builds a new instance without
    # re-validating, so the marker dict is accepted on the JSON fields.
    result = span.model_copy(update=updates)
    _log_truncation(
        getattr(span, "id", None),
        original_size_mb,
        max_size_mb,
        list(updates),
        still_oversized=sequence_splitter.get_payload_size_MB(result) > max_size_mb,
    )
    return result


def truncate_span_writes(
    spans: List[span_write.SpanWrite], max_size_mb: float
) -> List[span_write.SpanWrite]:
    """Truncate every span in a batch that exceeds the limit."""
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
    original_size_mb = sequence_splitter.get_payload_size_MB(span_kwargs)
    if original_size_mb <= max_size_mb:
        return

    def measure(overrides: Dict[str, Any]) -> float:
        return sequence_splitter.get_payload_size_MB({**span_kwargs, **overrides})

    updates = _plan_truncation(measure, _field_sizes_mb(span_kwargs.get), max_size_mb)
    if not updates:
        return

    span_kwargs.update(updates)
    _log_truncation(
        span_kwargs.get("id"),
        original_size_mb,
        max_size_mb,
        list(updates),
        still_oversized=sequence_splitter.get_payload_size_MB(span_kwargs)
        > max_size_mb,
    )
