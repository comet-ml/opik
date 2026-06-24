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
import json
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


# PydanticAI instrumentation v<=2 names the agent root span "agent run" and the
# tool span "running tool". v3+ follows the OpenTelemetry GenAI semantic
# conventions: "invoke_agent {agent_name}" and "execute_tool {tool_name}". The
# deps are unpinned, so accept either naming so the test survives version drift.
def _is_agent_run_span(name: str) -> bool:
    return name == "agent run" or name.startswith("invoke_agent")


def _is_tool_span(name: str) -> bool:
    return name == "running tool" or name.startswith("execute_tool")


# Tool result attribute key: "tool_response" (v<=2) or the GenAI semconv
# "gen_ai.tool.call.result" (v3+).
_TOOL_RESULT_KEYS = ("tool_response", "gen_ai.tool.call.result")


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
        lambda: (
            len(
                opik_client.search_spans(
                    project_name=opik_client.config.project_name, trace_id=trace_id
                )
            )
            >= 3
        ),
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
        "PydanticAI agent-run span was not nested under the tracked entrypoint"
    )
    # assert the name matches the agent-run span so a future hierarchy change
    # can't pass on parentage alone
    assert _is_agent_run_span(otel_root.name)

    # and the model/LLM span chained below the OTel root (descendant chaining)
    span_ids = {span.id for span in spans}
    assert any(
        span.parent_span_id in span_ids and span.id != otel_root.id
        for span in spans
        if span.parent_span_id == otel_root.id
    ), "Expected a model span nested under the PydanticAI 'agent run' span"


def test_pydantic_ai_logfire_with_opik_track__two_agent_calls__correct_nesting(
    opik_client: opik.Opik,
):
    """Two sequential agent calls inside one @opik.track entrypoint must produce
    two sibling 'agent run' subtrees under the entrypoint — each with its own
    model span and no cross-linking between the calls — all in a single trace."""
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

    agent = Agent(TestModel(custom_output_text="ok"))
    ID_STORAGE = {}

    @opik.track(name="run_entrypoint")
    def run() -> str:
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["entrypoint_span_id"] = opik_context.get_current_span_data().id
        agent.run_sync("first question")
        agent.run_sync("second question")
        return "done"

    run()
    opik.flush_tracker()
    logfire.force_flush()

    trace_id = ID_STORAGE["trace_id"]
    entrypoint_span_id = ID_STORAGE["entrypoint_span_id"]

    # entrypoint + 2x (agent run + model span) = 5 spans in a single trace
    if not synchronization.until(
        lambda: (
            len(
                opik_client.search_spans(
                    project_name=opik_client.config.project_name, trace_id=trace_id
                )
            )
            == 5
        ),
        max_try_seconds=15,
    ):
        raise AssertionError(
            "Expected 5 spans (entrypoint + two agent-run subtrees) in a single "
            "trace within timeout"
        )

    spans = opik_client.search_spans(
        project_name=opik_client.config.project_name,
        trace_id=trace_id,
    )

    # the tracked entrypoint is the single root of the trace
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=entrypoint_span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="run_entrypoint",
    )

    # both agent-run subtrees are siblings directly under the entrypoint
    agent_runs = [span for span in spans if _is_agent_run_span(span.name)]
    assert len(agent_runs) == 2
    for agent_run in agent_runs:
        verifiers.verify_span(
            opik_client=opik_client,
            span_id=agent_run.id,
            trace_id=trace_id,
            parent_span_id=entrypoint_span_id,
        )

    # each 'agent run' owns exactly one model span parented to that run alone —
    # with the 5-span total above, this rules out any cross-linking between calls
    for agent_run in agent_runs:
        children = [span for span in spans if span.parent_span_id == agent_run.id]
        assert len(children) == 1, "each agent run should own exactly one model span"
        verifiers.verify_span(
            opik_client=opik_client,
            span_id=children[0].id,
            trace_id=trace_id,
            parent_span_id=agent_run.id,
        )


def test_pydantic_ai_logfire__nested_track_and_otel__cross_origin_nesting(
    opik_client: opik.Opik,
):
    """Interleave native @opik.track spans with logfire/PydanticAI OTel spans at
    different depths. Each OTel 'agent run' must attach to the innermost active
    native span — one under the nested `inner_step`, the other under the
    top-level `outer_entrypoint` — so parent-child links are preserved across
    origins (native <-> OTel) within a single trace."""
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

    agent = Agent(TestModel(custom_output_text="ok"))
    ID_STORAGE = {}

    @opik.track(name="inner_step")
    def inner_step() -> None:
        ID_STORAGE["inner_span_id"] = opik_context.get_current_span_data().id
        agent.run_sync("inner question")

    @opik.track(name="outer_entrypoint")
    def outer() -> str:
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["outer_span_id"] = opik_context.get_current_span_data().id
        inner_step()
        agent.run_sync("outer question")
        return "done"

    outer()
    opik.flush_tracker()
    logfire.force_flush()

    trace_id = ID_STORAGE["trace_id"]
    outer_span_id = ID_STORAGE["outer_span_id"]
    inner_span_id = ID_STORAGE["inner_span_id"]

    # outer + inner (native) + 2x (agent run + model) = 6 spans in one trace
    if not synchronization.until(
        lambda: (
            len(
                opik_client.search_spans(
                    project_name=opik_client.config.project_name, trace_id=trace_id
                )
            )
            == 6
        ),
        max_try_seconds=15,
    ):
        raise AssertionError(
            "Expected 6 spans (nested native steps + two agent-run subtrees) in a "
            "single trace within timeout"
        )

    spans = opik_client.search_spans(
        project_name=opik_client.config.project_name,
        trace_id=trace_id,
    )

    # native spans: outer is the root, inner is its child
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=outer_span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="outer_entrypoint",
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=inner_span_id,
        trace_id=trace_id,
        parent_span_id=outer_span_id,
        name="inner_step",
    )

    # the two OTel agent-run spans attach to the innermost native span that was
    # active when each was started: one under inner_step, one under outer.
    agent_runs = [span for span in spans if _is_agent_run_span(span.name)]
    assert len(agent_runs) == 2
    assert {run.parent_span_id for run in agent_runs} == {inner_span_id, outer_span_id}
    for agent_run in agent_runs:
        verifiers.verify_span(
            opik_client=opik_client,
            span_id=agent_run.id,
            trace_id=trace_id,
            parent_span_id=agent_run.parent_span_id,
        )
        children = [span for span in spans if span.parent_span_id == agent_run.id]
        assert len(children) == 1, "each agent run should own exactly one model span"
        verifiers.verify_span(
            opik_client=opik_client,
            span_id=children[0].id,
            trace_id=trace_id,
            parent_span_id=agent_run.id,
        )


def test_pydantic_ai_logfire__tool_span_logs_its_output(
    opik_client: opik.Opik,
):
    """A PydanticAI tool's return value must land on the tool span's OUTPUT.

    The logfire instrumentation emits the result under a tool-result attribute
    (``tool_response`` in v<=2, ``gen_ai.tool.call.result`` in v3+); without an
    explicit mapping it falls into the default INPUT bucket, so the tool span
    renders no output in the UI.
    """
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

    agent = Agent(TestModel())

    @agent.tool_plain
    def get_weather(city: str) -> str:
        return f"Sunny in {city}"

    ID_STORAGE = {}

    @opik.track(name="run_entrypoint")
    def run() -> str:
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        return str(agent.run_sync("What is the weather?").output)

    run()
    opik.flush_tracker()
    logfire.force_flush()

    trace_id = ID_STORAGE["trace_id"]

    def _tool_span():
        spans = opik_client.search_spans(
            project_name=opik_client.config.project_name, trace_id=trace_id
        )
        tool_span = next((span for span in spans if _is_tool_span(span.name)), None)
        return tool_span if tool_span is not None and tool_span.output else None

    if not synchronization.until(lambda: _tool_span() is not None, max_try_seconds=15):
        raise AssertionError(
            "Expected the PydanticAI tool span to be ingested with a non-empty "
            "output within timeout"
        )

    tool_span = _tool_span()
    # the tool's return value is mapped to OUTPUT, not INPUT
    assert any(key in tool_span.output for key in _TOOL_RESULT_KEYS)
    assert "Sunny in" in json.dumps(tool_span.output)
    assert all(key not in (tool_span.input or {}) for key in _TOOL_RESULT_KEYS)


def test_pydantic_ai_logfire__tool_error_is_logged_as_error_info(
    opik_client: opik.Opik,
):
    """When a PydanticAI tool raises, the OTel ``exception`` event must surface as
    the Opik span's ``error_info`` instead of being buried in raw event metadata.
    """
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

    agent = Agent(TestModel())

    @agent.tool_plain
    def failing_tool() -> str:
        raise RuntimeError("boom: upstream 502")

    ID_STORAGE = {}

    @opik.track(name="run_entrypoint")
    def run() -> None:
        ID_STORAGE["trace_id"] = opik_context.get_current_trace_data().id
        try:
            agent.run_sync("call the tool")
        except Exception:
            # the tool error propagates out of the agent run; we only care that
            # it was recorded on the span, not about handling it here
            pass

    run()
    opik.flush_tracker()
    logfire.force_flush()

    trace_id = ID_STORAGE["trace_id"]

    def _error_spans():
        spans = opik_client.search_spans(
            project_name=opik_client.config.project_name, trace_id=trace_id
        )
        return [span for span in spans if span.error_info is not None]

    if not synchronization.until(lambda: len(_error_spans()) > 0, max_try_seconds=15):
        raise AssertionError(
            "Expected at least one span with error_info populated from the tool "
            "exception within timeout"
        )

    error_info = _error_spans()[0].error_info
    assert error_info.exception_type == "RuntimeError"
    assert "boom: upstream 502" in (error_info.message or "")
    assert error_info.traceback
