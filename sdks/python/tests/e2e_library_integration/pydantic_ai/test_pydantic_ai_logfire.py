"""
E2E library integration test for OPIK-6770.

Reproduces the ticket scenario: a PydanticAI agent instrumented via logfire
emits its own OTel spans, and the whole call is wrapped in ``@opik.track``.

Before the ``OpikSpanProcessor`` in-process fallback this produced *two*
separate traces — one SDK trace for the tracked entrypoint and one OTel trace
for the ``agent run`` / model spans, since ``@opik.track`` creates an Opik span
in Opik's context storage but no OTel span for logfire to inherit from.

With the fallback, registering ``OpikSpanProcessor`` on logfire's
``TracerProvider`` links the agent's root OTel span to the active tracked span,
producing a single trace with the model spans nested under the entrypoint.

A ``TestModel`` is used so the test is hermetic (no LLM provider key needed).
"""

import asyncio
import urllib.parse

import logfire
from pydantic_ai import Agent
from pydantic_ai.models.test import TestModel

from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

import opik
from opik import opik_context, config as opik_config, synchronization
from opik.integrations.otel import OpikSpanProcessor

from ...e2e import verifiers


def _build_otlp_exporter(config: opik_config.OpikConfig) -> OTLPSpanExporter:
    parsed = urllib.parse.urlparse(config.url_override)
    # different port for local dev vs test CI environment
    otel_path = (
        "/api/v1/private/otel/v1/traces"
        if parsed.port == 5173
        else "/v1/private/otel/v1/traces"
    )
    endpoint = urllib.parse.urljoin(config.url_override, otel_path)
    headers = {"Authorization": config.api_key or ""}
    if config.workspace:
        headers["Comet-Workspace"] = config.workspace
    headers["projectName"] = config.project_name
    return OTLPSpanExporter(endpoint=endpoint, headers=headers)


def test_pydantic_ai_logfire_with_opik_track__single_merged_trace(
    opik_client: opik.Opik,
):
    config = opik_config.OpikConfig()
    exporter = _build_otlp_exporter(config)

    logfire.configure(
        send_to_logfire=False,
        console=False,
        additional_span_processors=[
            BatchSpanProcessor(exporter),
            OpikSpanProcessor(),
        ],
    )
    logfire.instrument_pydantic_ai()

    agent = Agent(TestModel(custom_output_text="4"))
    ID_STORAGE = {}

    @opik.track(name="run_entrypoint")
    async def run(question: str) -> str:
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["entrypoint_span_id"] = opik_context.get_current_span_data().id
        result = await agent.run(question)
        return result.output

    asyncio.run(run("What is 2+2?"))
    opik.flush_tracker()
    logfire.force_flush()

    trace_id = ID_STORAGE["trace_id"]
    entrypoint_span_id = ID_STORAGE["entrypoint_span_id"]

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="run_entrypoint",
        input={"question": "What is 2+2?"},
        output={"output": "4"},
    )

    # entrypoint + at least the "agent run" and a model span all under one trace
    if not synchronization.until(
        lambda: len(
            opik_client.search_spans(
                project_name=opik_client.config.project_name, trace_id=trace_id
            )
        )
        >= 3,
        max_try_seconds=15,
    ):
        raise AssertionError(
            "Expected the PydanticAI/logfire OTel spans to be ingested under the "
            "@opik.track trace within timeout"
        )

    spans = opik_client.search_spans(
        project_name=opik_client.config.project_name,
        trace_id=trace_id,
    )

    # every span belongs to the single tracked trace — no orphan OTel trace
    assert all(span.trace_id == trace_id for span in spans)

    # exactly one root, and it is the tracked entrypoint span
    roots = [span for span in spans if span.parent_span_id is None]
    assert len(roots) == 1
    assert roots[0].id == entrypoint_span_id
    assert roots[0].name == "run_entrypoint"

    # the agent's root OTel span attached directly under the entrypoint span
    otel_root = next(
        (span for span in spans if span.parent_span_id == entrypoint_span_id), None
    )
    assert otel_root is not None, (
        "PydanticAI 'agent run' span was not nested under the tracked entrypoint"
    )

    # and the model/LLM span chained below the OTel root (descendant chaining)
    span_ids = {span.id for span in spans}
    assert any(
        span.parent_span_id in span_ids and span.id != otel_root.id
        for span in spans
        if span.parent_span_id == otel_root.id
    ), "Expected a model span nested under the PydanticAI 'agent run' span"
