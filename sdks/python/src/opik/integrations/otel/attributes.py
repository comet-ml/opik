"""Canonical OTel attribute keys for Opik distributed tracing.

These are the attribute names set on OpenTelemetry spans (and read by the
Opik backend's ``OpenTelemetryMapper``). They mirror the constants in
``GeneralMappingRules.java`` on the backend side.
"""

OPIK_TRACE_ID = "opik.trace_id"
OPIK_SPAN_ID = "opik.span_id"
OPIK_PARENT_SPAN_ID = "opik.parent_span_id"
