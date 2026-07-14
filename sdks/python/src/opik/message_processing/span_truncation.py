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
from typing import Any, Dict, List, Optional

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
    total_size_mb: float,
    max_size_mb: float,
    truncated_fields: List[str],
) -> None:
    LOGGER.warning(
        "Span '%s' (%.1f MB) exceeded the per-span size limit of %s MB; "
        "truncated field(s): %s. Log large payloads as attachments to avoid "
        "truncation: %s",
        span_id,
        total_size_mb,
        max_size_mb,
        ", ".join(truncated_fields),
        _DOCS_URL,
    )


def _plan_field_truncation(
    field_values: Dict[str, Any], total_size_mb: float, max_size_mb: float
) -> Dict[str, Any]:
    """Decide which fields to replace with a marker.

    Replaces the largest fields first until the projected span size drops below
    the limit. Returns a mapping ``{field_name: marker}`` (empty if nothing needs
    truncating).
    """
    field_sizes_mb = {
        name: sequence_splitter.get_payload_size_MB(value)
        for name, value in field_values.items()
        if value is not None
    }

    updates: Dict[str, Any] = {}
    projected_size_mb = total_size_mb
    for name in sorted(field_sizes_mb, key=lambda n: field_sizes_mb[n], reverse=True):
        if projected_size_mb <= max_size_mb:
            break
        original_size_bytes = int(field_sizes_mb[name] * 1024 * 1024)
        updates[name] = _truncation_marker(name, original_size_bytes, max_size_mb)
        projected_size_mb -= field_sizes_mb[name]

    return updates


def truncate_span_write_if_needed(
    span: span_write.SpanWrite, max_size_mb: float
) -> span_write.SpanWrite:
    """Return a copy of ``span`` with oversized fields truncated, or ``span`` unchanged."""
    total_size_mb = sequence_splitter.get_payload_size_MB(span)
    if total_size_mb <= max_size_mb:
        return span

    field_values = {name: getattr(span, name, None) for name in _TRUNCATABLE_FIELDS}
    updates = _plan_field_truncation(field_values, total_size_mb, max_size_mb)
    if not updates:
        return span

    _log_truncation(
        getattr(span, "id", None), total_size_mb, max_size_mb, list(updates)
    )
    # SpanWrite is frozen; model_copy(update=...) builds a new instance without
    # re-validating, so the marker dict is accepted on the JSON fields.
    return span.model_copy(update=updates)


def truncate_span_writes(
    spans: List[span_write.SpanWrite], max_size_mb: float
) -> List[span_write.SpanWrite]:
    """Truncate every span in a batch that exceeds the limit."""
    return [truncate_span_write_if_needed(span, max_size_mb) for span in spans]


def truncate_create_span_kwargs_if_needed(
    create_span_kwargs: Dict[str, Any], max_size_mb: float
) -> None:
    """Truncate oversized fields on the single-span create payload, in place.

    Used on the non-batched path (``use_batching=False``).
    """
    total_size_mb = sequence_splitter.get_payload_size_MB(create_span_kwargs)
    if total_size_mb <= max_size_mb:
        return

    field_values = {name: create_span_kwargs.get(name) for name in _TRUNCATABLE_FIELDS}
    updates = _plan_field_truncation(field_values, total_size_mb, max_size_mb)
    if not updates:
        return

    _log_truncation(
        create_span_kwargs.get("id"), total_size_mb, max_size_mb, list(updates)
    )
    create_span_kwargs.update(updates)
