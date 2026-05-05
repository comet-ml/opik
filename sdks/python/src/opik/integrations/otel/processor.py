"""``OpikSpanProcessor`` — propagates Opik trace context across an OTel subtree.

Background
----------
``distributed_trace.attach_to_parent`` only sets ``opik.trace_id`` /
``opik.parent_span_id`` / ``opik.span_id`` on the *boundary* OTel span. Children
created inside that span via ``start_as_current_span`` inherit OTel context but
not those attributes, so the backend's per-span attribute inspection misses them
and they end up orphaned in a synthetic Opik trace.

This processor closes the gap: on every span start it inspects the parent OTel
span (and OTel baggage, for cross-process boundaries). If the parent already
carries ``opik.trace_id`` + ``opik.span_id``, it mints a fresh ``opik.span_id``
for the new span and threads the parent's value as ``opik.parent_span_id``. The
backend ``OpenTelemetryMapper`` fast path then uses these UUIDs verbatim.

Spans whose parent has no Opik attributes (and no baggage) are left untouched,
so today's SHA-256 + Redis path still applies — the processor only kicks in for
attached subtrees originating from ``attach_to_parent``.

Usage
-----
.. code-block:: python

    from opentelemetry.sdk.trace import TracerProvider
    from opik.integrations.otel import OpikSpanProcessor

    provider = TracerProvider()
    provider.add_span_processor(OpikSpanProcessor())
    # ... add your exporter processor as well
"""

import logging
from typing import Optional, NamedTuple

from opentelemetry import baggage, trace
from opentelemetry.context import Context
from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.trace import Span

from opik import id_helpers
from opik.integrations.otel import attributes as otel_attributes


LOGGER = logging.getLogger(__name__)


class InheritedContext(NamedTuple):
    trace_id: str
    parent_span_id: Optional[str]


def _resolve_inherited(parent_context: Optional[Context]) -> Optional[InheritedContext]:
    # 1) In-process: pull from the parent OTel span's attributes. The parent must carry
    # both opik.trace_id and opik.span_id — this pair is set together by attach_to_parent
    # on the boundary and by this processor on every inherited descendant. A parent
    # that has trace_id but not span_id means a misconfigured upstream, and we don't try
    # to guess.
    parent_span = trace.get_current_span(parent_context)
    if parent_span is not None and parent_span is not trace.INVALID_SPAN:
        attrs = getattr(parent_span, "attributes", None) or {}
        parent_trace_id = attrs.get(otel_attributes.OPIK_TRACE_ID)
        parent_span_id = attrs.get(otel_attributes.OPIK_SPAN_ID)

        # Both absent → parent isn't part of an Opik subtree; fall through to baggage.
        if parent_trace_id is not None or parent_span_id is not None:
            if not id_helpers.is_valid_uuid_v7(parent_trace_id):
                LOGGER.warning(
                    "Parent span attribute '%s' is missing or not a valid UUID v7: %r; "
                    "ignoring inherited Opik context.",
                    otel_attributes.OPIK_TRACE_ID,
                    parent_trace_id,
                )
            elif not id_helpers.is_valid_uuid_v7(parent_span_id):
                LOGGER.warning(
                    "Parent span attribute '%s' is missing or not a valid UUID v7: %r; "
                    "ignoring inherited Opik context.",
                    otel_attributes.OPIK_SPAN_ID,
                    parent_span_id,
                )
            else:
                return InheritedContext(
                    trace_id=parent_trace_id,
                    parent_span_id=parent_span_id,
                )

    # 2) Cross-process: pull from OTel baggage (propagated via the W3C
    # `baggage` header). Used when a child process inherits OTel context
    # from an upstream service that already had Opik IDs.
    baggage_trace_id = baggage.get_baggage(
        otel_attributes.OPIK_TRACE_ID, parent_context
    )
    if baggage_trace_id is None:
        # No Opik context in baggage — the common case for spans outside an
        # attached subtree. Leave the span untouched.
        return None
    if not id_helpers.is_valid_uuid_v7(baggage_trace_id):
        LOGGER.warning(
            "Baggage value for '%s' is not a valid UUID v7: %r; ignoring.",
            otel_attributes.OPIK_TRACE_ID,
            baggage_trace_id,
        )
        return None

    baggage_parent_span_id = baggage.get_baggage(
        otel_attributes.OPIK_SPAN_ID, parent_context
    )
    if baggage_parent_span_id is not None and not id_helpers.is_valid_uuid_v7(
        baggage_parent_span_id
    ):
        LOGGER.warning(
            "Baggage value for '%s' is not a valid UUID v7: %r; "
            "attaching to '%s' without a parent span id.",
            otel_attributes.OPIK_SPAN_ID,
            baggage_parent_span_id,
            otel_attributes.OPIK_TRACE_ID,
        )
        baggage_parent_span_id = None

    return InheritedContext(
        trace_id=baggage_trace_id,
        parent_span_id=(
            baggage_parent_span_id
            if id_helpers.is_valid_uuid_v7(baggage_parent_span_id)
            else None
        ),
    )


class OpikSpanProcessor(SpanProcessor):
    """OTel ``SpanProcessor`` that propagates Opik IDs down an attached subtree.

    Register on the same ``TracerProvider`` as your OTLP exporter processor.
    Order does not matter — this processor only mutates span attributes at start.
    """

    def on_start(self, span: Span, parent_context: Optional[Context] = None) -> None:
        inherited = _resolve_inherited(parent_context)
        if inherited is None:
            return

        try:
            span.set_attribute(otel_attributes.OPIK_TRACE_ID, inherited.trace_id)
            span.set_attribute(otel_attributes.OPIK_SPAN_ID, id_helpers.generate_id())
            if inherited.parent_span_id is not None:
                span.set_attribute(
                    otel_attributes.OPIK_PARENT_SPAN_ID, inherited.parent_span_id
                )
        except (
            Exception
        ) as e:  # pragma: no cover - per OTel contract on_start must not throw
            LOGGER.error("Failed to set Opik attributes on span: %s", e, exc_info=True)

    def on_end(self, span: Span) -> None:
        return None

    def shutdown(self) -> None:
        return None

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True
