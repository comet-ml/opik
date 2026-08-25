from typing import Any

import pydantic
import pytest
from pydantic_ai import Agent, InstrumentationSettings
from pydantic_ai.models.test import TestModel

import opik
from opik import context_storage
from opik.integrations.pydantic_ai import track_pydantic_ai
from opik.integrations.pydantic_ai.span_data_parsers import build_usage
from tests.testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)

PROJECT_NAME = "pydantic-ai-test"


def _instrument(agent: Agent[Any, Any]) -> Agent[Any, Any]:
    return track_pydantic_ai(agent, project_name=PROJECT_NAME)


def _llm_span(
    *,
    input: Any = ANY_DICT,
    output: Any = ANY_DICT,
    project_name: Any = PROJECT_NAME,
) -> SpanModel:
    return SpanModel(
        id=ANY_BUT_NONE,
        name="chat test",
        type="llm",
        input=input,
        output=output,
        usage=ANY_DICT,
        model="test",
        provider="test",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        spans=[],
    )


def _agent_span(
    *,
    name: str,
    prompt: str,
    output: Any,
    spans: list[SpanModel],
    project_name: Any = PROJECT_NAME,
    error_info: Any = None,
) -> SpanModel:
    return SpanModel(
        id=ANY_BUT_NONE,
        name=name,
        type="general",
        input={"prompt": prompt},
        output=output,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        error_info=error_info,
        spans=spans,
    )


def _standalone_trace(
    *,
    name: str,
    prompt: str,
    output: Any,
    spans: list[SpanModel],
    error_info: Any = None,
) -> TraceModel:
    return TraceModel(
        id=ANY_BUT_NONE,
        name=name,
        project_name=PROJECT_NAME,
        input={"prompt": prompt},
        output=output,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=error_info,
        spans=spans,
    )


def test_pydantic_ai__standalone_run__creates_trace_with_nested_spans(fake_backend):
    agent = _instrument(Agent(TestModel(), name="my_agent"))

    result = agent.run_sync("hello")
    opik.flush_tracker()

    expected_output = {"response": result.output}
    expected_llm_input = {
        "messages": [{"role": "user", "parts": [{"type": "text", "content": "hello"}]}]
    }
    expected_llm_output = {
        "messages": [
            {
                "role": "assistant",
                "parts": [{"type": "text", "content": result.output}],
            }
        ]
    }
    expected_agent_span = _agent_span(
        name="run my_agent",
        prompt="hello",
        output=expected_output,
        spans=[_llm_span(input=expected_llm_input, output=expected_llm_output)],
    )
    expected_trace = _standalone_trace(
        name="run my_agent",
        prompt="hello",
        output=expected_output,
        spans=[expected_agent_span],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_pydantic_ai__with_tool__creates_nested_tool_span(fake_backend):
    def add(x: int) -> int:
        """Add one to x."""
        return x + 1

    agent = _instrument(Agent(TestModel(), name="tool_agent", tools=[add]))

    result = agent.run_sync("run the tool")
    opik.flush_tracker()

    expected_output = {"response": {"add": 1}}
    expected_agent_span = _agent_span(
        name="run tool_agent",
        prompt="run the tool",
        output=expected_output,
        spans=[
            _llm_span(),
            SpanModel(
                id=ANY_BUT_NONE,
                name="execute_tool: add",
                type="tool",
                input={"tool_name": "add", "arguments": {"x": 0}},
                output={"result": 1},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                spans=[],
            ),
            _llm_span(),
        ],
    )
    expected_trace = _standalone_trace(
        name="run tool_agent",
        prompt="run the tool",
        output=expected_output,
        spans=[expected_agent_span],
    )

    assert result.output == '{"add":1}'
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_pydantic_ai__tool_error__logs_error_on_trace_agent_and_tool(fake_backend):
    def explode(x: int) -> int:
        """Raise an error."""
        raise RuntimeError("tool failed")

    agent = _instrument(
        Agent(TestModel(call_tools="all"), name="error_agent", tools=[explode])
    )

    with pytest.raises(RuntimeError, match="tool failed"):
        agent.run_sync("run the tool")
    opik.flush_tracker()

    error_info = {
        "exception_type": ANY_STRING.containing("RuntimeError"),
        "message": "tool failed",
        "traceback": ANY_STRING.containing("RuntimeError: tool failed"),
    }
    expected_agent_span = _agent_span(
        name="run error_agent",
        prompt="run the tool",
        output=None,
        error_info=error_info,
        spans=[
            _llm_span(),
            SpanModel(
                id=ANY_BUT_NONE,
                name="execute_tool: explode",
                type="tool",
                input={"tool_name": "explode", "arguments": {"x": 0}},
                output=None,
                error_info=error_info,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=PROJECT_NAME,
                spans=[],
            ),
        ],
    )
    expected_trace = _standalone_trace(
        name="run error_agent",
        prompt="run the tool",
        output=None,
        error_info=error_info,
        spans=[expected_agent_span],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_pydantic_ai__structured_output__captures_concrete_output(fake_backend):
    class Result(pydantic.BaseModel):
        answer: str

    agent = _instrument(Agent(TestModel(), name="structured_agent", output_type=Result))

    result = agent.run_sync("give structured output")
    opik.flush_tracker()

    expected_output = {"response": result.output.model_dump()}
    expected_agent_span = _agent_span(
        name="run structured_agent",
        prompt="give structured output",
        output=expected_output,
        spans=[_llm_span()],
    )
    expected_trace = _standalone_trace(
        name="run structured_agent",
        prompt="give structured output",
        output=expected_output,
        spans=[expected_agent_span],
    )

    assert result.output == Result(answer="a")
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_pydantic_ai__nested_under_track__preserves_full_tree(fake_backend):
    agent = _instrument(Agent(TestModel(), name="inner_agent"))

    @opik.track(name="entrypoint")
    def run(question: str) -> str:
        return str(agent.run_sync(question).output)

    output = run("nested question")
    opik.flush_tracker()

    expected_agent_output = {"response": output}
    expected_agent_span = _agent_span(
        name="run inner_agent",
        prompt="nested question",
        output=expected_agent_output,
        spans=[_llm_span(project_name=ANY)],
        project_name=ANY,
    )
    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="entrypoint",
        input={"question": "nested question"},
        output={"output": output},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="entrypoint",
                type="general",
                input={"question": "nested question"},
                output={"output": output},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[expected_agent_span],
            )
        ],
    )

    assert output == "success (no tool calls)"
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_pydantic_ai__thread_id_from_metadata__set_on_trace(fake_backend):
    agent = _instrument(Agent(TestModel(), name="thread_agent"))

    agent.run_sync("hi", metadata={"opik.thread_id": "thread-123"})
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].thread_id == "thread-123"


def test_pydantic_ai__message_history__captures_current_prompt(fake_backend):
    agent = _instrument(Agent(TestModel(), name="history_agent"))
    first_result = agent.run_sync("first prompt")

    second_result = agent.run_sync(
        "second prompt", message_history=first_result.all_messages()
    )
    opik.flush_tracker()

    assert second_result.output == "success (no tool calls)"
    assert len(fake_backend.trace_trees) == 2
    assert fake_backend.trace_trees[1].input == {"prompt": "second prompt"}
    assert fake_backend.trace_trees[1].spans[0].input == {"prompt": "second prompt"}


def test_pydantic_ai__reserved_metadata__enriches_run_without_reparsing_llm_usage(
    fake_backend,
):
    agent = _instrument(Agent(TestModel(), name="metadata_agent"))

    agent.run_sync(
        "hello",
        metadata={
            "opik.metadata": {"experiment": "baseline"},
            "opik.provider": "custom-provider",
        },
    )
    opik.flush_tracker()

    trace = fake_backend.trace_trees[0]
    agent_span = trace.spans[0]
    llm_span = agent_span.spans[0]
    assert trace.metadata == {"experiment": "baseline"}
    assert agent_span.metadata == {"experiment": "baseline"}
    assert agent_span.provider == "custom-provider"
    assert llm_span.provider == "test"
    assert llm_span.usage == {
        "prompt_tokens": 51,
        "completion_tokens": 4,
        "total_tokens": 55,
    }


def test_pydantic_ai__malformed_reserved_metadata__still_logs_run(fake_backend):
    agent = _instrument(Agent(TestModel(), name="metadata_agent"))

    result = agent.run_sync("hello", metadata={"opik.metadata": "invalid"})
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].output == {"response": result.output}


def test_pydantic_ai__span_start_failure__cleans_context(fake_backend, monkeypatch):
    client = opik.get_global_client()

    def fail_span_start(**kwargs: Any) -> None:
        raise RuntimeError("span start failed")

    monkeypatch.setattr(client, "__internal_api__span__", fail_span_start)
    agent = _instrument(Agent(TestModel(), name="failing_agent"))

    result = agent.run_sync("hello")

    assert result.output == "success (no tool calls)"
    assert context_storage.span_data_stack_empty()
    assert context_storage.get_trace_data() is None
    assert fake_backend.trace_trees == []


def test_track_pydantic_ai__agent__returns_same_instrumented_agent():
    agent = Agent(TestModel(), name="return_agent")

    returned = track_pydantic_ai(agent)

    assert returned is agent
    assert isinstance(agent.instrument, InstrumentationSettings)


def test_track_pydantic_ai__no_agent__instruments_future_agents(fake_backend):
    Agent.instrument_all(False)
    try:
        track_pydantic_ai(project_name=PROJECT_NAME)
        agent = Agent(TestModel(), name="global_agent")

        result = agent.run_sync("hello")
        opik.flush_tracker()
    finally:
        Agent.instrument_all(False)

    expected_output = {"response": result.output}
    expected_agent_span = _agent_span(
        name="run global_agent",
        prompt="hello",
        output=expected_output,
        spans=[_llm_span()],
    )
    expected_trace = _standalone_trace(
        name="run global_agent",
        prompt="hello",
        output=expected_output,
        spans=[expected_agent_span],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_build_usage__no_tokens__returns_none():
    assert build_usage({}, provider="anthropic") is None


def test_build_usage__plain_tokens__returns_normalized_opik_usage():
    usage = build_usage(
        {"gen_ai.usage.input_tokens": 51, "gen_ai.usage.output_tokens": 4},
        provider="test",
    )

    assert usage is not None
    assert usage.to_backend_compatible_full_usage_dict() == {
        "prompt_tokens": 51,
        "completion_tokens": 4,
        "total_tokens": 55,
    }


@pytest.mark.parametrize(
    ("provider", "expected_provider_usage"),
    [
        (
            "anthropic",
            {
                "original_usage.input_tokens": 50,
                "original_usage.output_tokens": 10,
                "original_usage.cache_read_input_tokens": 30,
                "original_usage.cache_creation_input_tokens": 20,
            },
        ),
        (
            "bedrock",
            {
                "original_usage.inputTokens": 50,
                "original_usage.outputTokens": 10,
                "original_usage.cacheReadInputTokens": 30,
                "original_usage.cacheWriteInputTokens": 20,
            },
        ),
        (
            "openai",
            {
                "original_usage.prompt_tokens": 100,
                "original_usage.completion_tokens": 10,
                "original_usage.total_tokens": 110,
                "original_usage.prompt_tokens_details.cached_tokens": 30,
                "original_usage.cache_creation_input_tokens": 20,
            },
        ),
        (
            "google",
            {
                "original_usage.prompt_token_count": 100,
                "original_usage.candidates_token_count": 10,
                "original_usage.total_token_count": 110,
                "original_usage.cached_content_token_count": 30,
                "original_usage.cache_creation_input_tokens": 20,
            },
        ),
    ],
)
def test_build_usage__cache__preserves_normalized_and_provider_tokens(
    provider: str, expected_provider_usage: dict[str, int]
):
    usage = build_usage(
        {
            "gen_ai.usage.input_tokens": 100,
            "gen_ai.usage.output_tokens": 10,
            "gen_ai.usage.cache_read.input_tokens": 30,
            "gen_ai.usage.cache_creation.input_tokens": 20,
        },
        provider=provider,
    )

    assert usage is not None
    assert usage.to_backend_compatible_full_usage_dict() == {
        "prompt_tokens": 100,
        "completion_tokens": 10,
        "total_tokens": 110,
        **expected_provider_usage,
    }
