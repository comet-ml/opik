"""
E2E tests for the opik.otel distributed tracing module.

These tests verify the full roundtrip through the Opik backend:
  Service A (Opik SDK) creates a trace/span and extracts distributed headers →
  Service B receives headers, creates OTel spans with opik attributes,
  and sends them to the Opik backend via OTLP →
  Verify the OTel spans appear in the backend linked to the parent trace.
"""

import urllib.parse

import opik
from opik import opik_context, config as opik_config, synchronization
from opik.otel import distributed_trace

from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

from ...e2e import verifiers


def _build_otlp_exporter(config: opik_config.OpikConfig) -> OTLPSpanExporter:
    """Build an OTLP exporter pointing at the Opik backend."""
    otel_path = (
        "/api/v1/private/otel/v1/traces"
        if config.url_override.endswith("5173")
        else "/v1/private/otel/v1/traces"
    )
    endpoint = urllib.parse.urljoin(config.url_override, otel_path)
    headers = {"Authorization": config.api_key or ""}
    if config.workspace:
        headers["Comet-Workspace"] = config.workspace
    headers["projectName"] = config.project_name
    return OTLPSpanExporter(endpoint=endpoint, headers=headers)


def _create_otel_tracer(config: opik_config.OpikConfig):
    """Create an OTel TracerProvider and tracer configured for the Opik OTLP endpoint."""
    resource = Resource.create({"service.name": "e2e-otel-test"})
    provider = TracerProvider(resource=resource)
    exporter = _build_otlp_exporter(config)
    provider.add_span_processor(BatchSpanProcessor(exporter))
    return provider.get_tracer("e2e-otel-test-tracer"), provider


def test_otel_distributed_trace_roundtrip__happyflow(opik_client: opik.Opik):
    """
    Full E2E roundtrip through the Opik backend:
    1. Service A: create Opik trace and span, extract distributed headers
    2. Service B: create OTel span with opik attributes via attach_to_parent,
       send to Opik backend OTLP endpoint
    3. Verify: the OTel span appears in the backend under the correct trace
       with the correct parent-child linkage
    """
    ID_STORAGE = {}

    # --- Service A: create parent trace/span and extract headers ---
    @opik.track(name="service-a-parent")
    def service_a_handler(request):
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent_span_id"] = opik_context.get_current_span_data().id
        ID_STORAGE["headers"] = opik_context.get_distributed_trace_headers()
        return "service-a-response"

    service_a_handler("service-a-request")
    opik.flush_tracker()

    # --- Service B: create OTel span with opik distributed trace attributes ---
    config = opik_config.OpikConfig()
    tracer, provider = _create_otel_tracer(config)

    distributed_headers = ID_STORAGE["headers"]
    with tracer.start_as_current_span("service-b-otel-span") as otel_span:
        distributed_trace.attach_to_parent(otel_span, distributed_headers)
        otel_span.set_attribute("input", "service-b-input")
        otel_span.set_attribute("output", "service-b-output")

    provider.force_flush()
    provider.shutdown()

    # --- Verify: parent trace and span in Opik backend ---
    trace_id = ID_STORAGE["trace_id"]
    parent_span_id = ID_STORAGE["parent_span_id"]
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="service-a-parent",
        input={"request": "service-a-request"},
        output={"output": "service-a-response"},
    )

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=parent_span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="service-a-parent",
        input={"request": "service-a-request"},
        output={"output": "service-a-response"},
    )

    if not synchronization.until(
        lambda: len(
            opik_client.search_spans(
                project_name=opik_client.config.project_name, trace_id=trace_id
            )
        )
        == 2,
        max_try_seconds=10,
    ):
        raise AssertionError(
            "Expected 2 spans in Opik backend: parent (Service A) and attached (Service B) within timeout"
        )

    spans = opik_client.search_spans(
        project_name=opik_client.config.project_name,
        trace_id=trace_id,
    )

    attached_span = next(
        iter([span for span in spans if span.name == "service-b-otel-span"]), None
    )
    assert attached_span is not None, "service-b-otel-span not found in Opik backend"
    assert attached_span.parent_span_id == parent_span_id
    assert attached_span.trace_id == trace_id
