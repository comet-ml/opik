from typing import List

import pydantic
from pydantic_ai import Agent
from pydantic_ai.models.instrumented import InstrumentationSettings
from pydantic_ai.models.test import TestModel

import opik
from opik.integrations.pydantic_ai import OpikSpanProcessor, track_pydantic_ai


def _instrument(agent: Agent) -> None:
    """Instrument a single agent so global state does not leak across tests."""
    agent.instrument = InstrumentationSettings(
        tracer_provider=_provider_with_opik_processor()
    )


def _provider_with_opik_processor():
    from opentelemetry.sdk.trace import TracerProvider

    provider = TracerProvider()
    provider.add_span_processor(OpikSpanProcessor(project_name="pydantic-ai-test"))
    return provider


def _flatten(span_models) -> List:
    result = []
    for span in span_models:
        result.append(span)
        result.extend(_flatten(span.spans))
    return result


def test_pydantic_ai__standalone_run__creates_trace_with_nested_spans(fake_backend):
    agent = Agent(TestModel(), name="my_agent")
    _instrument(agent)

    result = agent.run_sync("hello")

    opik.flush_tracker()

    assert result.output is not None
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert trace_tree.name == "run my_agent"
    assert trace_tree.project_name == "pydantic-ai-test"
    assert trace_tree.input == {"prompt": "hello"}
    assert trace_tree.output is not None

    root_span = trace_tree.spans[0]
    assert root_span.name == "run my_agent"
    assert root_span.type == "general"

    llm_spans = [s for s in _flatten(trace_tree.spans) if s.type == "llm"]
    assert len(llm_spans) >= 1
    assert all(s.usage is not None for s in llm_spans)
    assert all(s.model == "test" for s in llm_spans)


def test_pydantic_ai__with_tool__creates_tool_span(fake_backend):
    def add(x: int) -> int:
        """Add one to x."""
        return x + 1

    agent = Agent(TestModel(), name="tool_agent", tools=[add])
    _instrument(agent)

    agent.run_sync("run the tool")

    opik.flush_tracker()

    all_spans = _flatten(fake_backend.trace_trees[0].spans)
    tool_spans = [s for s in all_spans if s.type == "tool"]
    assert len(tool_spans) >= 1
    assert any("add" in (s.name or "") for s in tool_spans)
    assert any(
        (s.input or {}).get("tool_name") == "add" for s in tool_spans
    )


def test_pydantic_ai__structured_output__captured_on_trace(fake_backend):
    class Result(pydantic.BaseModel):
        answer: str

    agent = Agent(TestModel(), name="structured_agent", output_type=Result)
    _instrument(agent)

    agent.run_sync("give structured output")

    opik.flush_tracker()

    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.output is not None
    assert "response" in trace_tree.output


def test_pydantic_ai__nested_under_track__single_trace(fake_backend):
    agent = Agent(TestModel(), name="inner_agent")
    _instrument(agent)

    @opik.track(name="entrypoint")
    def run(question: str) -> str:
        return str(agent.run_sync(question).output)

    run("nested question")

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.name == "entrypoint"

    agent_spans = [
        s for s in _flatten(trace_tree.spans) if (s.name or "").startswith("run ")
    ]
    assert len(agent_spans) == 1


def test_pydantic_ai__thread_id_from_metadata__set_on_trace(fake_backend):
    agent = Agent(TestModel(), name="thread_agent")
    _instrument(agent)

    agent.run_sync("hi", metadata={"opik.thread_id": "thread-123"})

    opik.flush_tracker()

    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.thread_id == "thread-123"


def test_track_pydantic_ai__returns_instrumented_agent():
    agent = Agent(TestModel(), name="return_agent")
    returned = track_pydantic_ai(agent)
    assert returned is agent
    assert isinstance(agent.instrument, InstrumentationSettings)
