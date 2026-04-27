import opik
import pytest

pytest.importorskip("pyagentspec")

from opik.integrations.agentspec import AgentSpecInstrumentor, OpikSpanProcessor
from pyagentspec.llms import OpenAiConfig
from pyagentspec.tools import ClientTool
from pyagentspec.tracing.events import (
    LlmGenerationRequest,
    LlmGenerationResponse,
    ToolExecutionRequest,
    ToolExecutionResponse,
)
from pyagentspec.tracing.messages.message import Message
from pyagentspec.tracing.spans import LlmGenerationSpan, ToolExecutionSpan
from pyagentspec.tracing.trace import Trace, get_trace
from ... import llm_constants
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


@pytest.fixture
def flush_tracker():
    # Make sure that
    # - traces don't leak across tests
    # - traces are sent before being checked
    try:
        yield opik.flush_tracker
    finally:
        opik.flush_tracker()


def test_opik_span_processor_tool_and_llm_spans_are_forwarded_to_opik(
    fake_backend,
    flush_tracker,
):
    project_name = "agentspec-integration-test"
    tool = ClientTool(name="lookup_weather")
    llm_config = OpenAiConfig(name="demo-model", model_id=llm_constants.OPENAI_GPT_NANO)
    span_processor = OpikSpanProcessor(
        project_name=project_name,
        mask_sensitive_information=False,
    )

    with Trace(name="AgentSpec workflow", span_processors=[span_processor]):
        with ToolExecutionSpan(
            name="weather_tool",
            tool=tool,
            events=[
                ToolExecutionRequest(
                    tool=tool,
                    inputs={"city": "Zurich"},
                    request_id="tool-request",
                ),
                ToolExecutionResponse(
                    tool=tool,
                    outputs={"temperature": "18C"},
                    request_id="tool-request",
                ),
            ],
        ):
            pass

        with LlmGenerationSpan(
            name="llm_generation",
            llm_config=llm_config,
            events=[
                LlmGenerationRequest(
                    llm_config=llm_config,
                    prompt=[Message(content="my prompt", role="system", sender="me")],
                    tools=[],
                    request_id="llm-request",
                ),
                LlmGenerationResponse(
                    llm_config=llm_config,
                    content="sunny",
                    request_id="llm-request",
                    input_tokens=11,
                    output_tokens=4,
                ),
            ],
        ):
            pass

    flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="AgentSpec workflow",
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        end_time=None,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RootSpan",
                type="general",
                project_name=project_name,
                input={},
                output=None,
                metadata=ANY_DICT.containing({"events": []}),
                start_time=ANY_BUT_NONE,
                end_time=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="weather_tool",
                        type="tool",
                        project_name=project_name,
                        input={"city": "Zurich"},
                        output={"temperature": "18C"},
                        metadata=ANY_DICT.containing({"events": ANY_LIST}),
                        start_time=ANY_BUT_NONE,
                        end_time=None,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="llm_generation",
                        type="llm",
                        project_name=project_name,
                        model="demo-model",
                        input=ANY_DICT.containing(
                            {
                                "request_id": "llm-request",
                                "prompt": [
                                    {
                                        "id": None,
                                        "content": "my prompt",
                                        "role": "system",
                                        "sender": "me",
                                    }
                                ],
                            }
                        ),
                        output={
                            "response": "sunny",
                            "tool_calls": [],
                            "completion_id": None,
                        },
                        usage=ANY_DICT.containing(
                            {
                                "prompt_tokens": 11,
                                "completion_tokens": 4,
                                "total_tokens": 15,
                            }
                        ),
                        metadata=ANY_DICT.containing({"events": ANY_LIST}),
                        start_time=ANY_BUT_NONE,
                        end_time=None,
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert len(trace_tree.spans[0].spans[0].metadata["events"]) == 2
    assert len(trace_tree.spans[0].spans[1].metadata["events"]) == 2


def test_agentspec_instrumentor_context_manager_records_spans_and_cleans_up(
    fake_backend,
    flush_tracker,
):
    project_name = "agentspec-instrumentor-test"
    tool = ClientTool(name="lookup_time")
    instrumentor = AgentSpecInstrumentor()

    with instrumentor.instrument_context(
        project_name=project_name,
        mask_sensitive_information=False,
    ):
        assert get_trace() is not None

        with ToolExecutionSpan(
            name="time_tool",
            tool=tool,
            events=[
                ToolExecutionRequest(
                    tool=tool,
                    inputs={"timezone": "Europe/Zurich"},
                    request_id="tool-request",
                ),
                ToolExecutionResponse(
                    tool=tool,
                    outputs={"time": "09:30"},
                    request_id="tool-request",
                ),
            ],
        ):
            pass

    flush_tracker()

    assert get_trace() is None
    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="Trace",
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        end_time=None,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RootSpan",
                type="general",
                project_name=project_name,
                input={},
                output=None,
                metadata=ANY_DICT.containing({"events": []}),
                start_time=ANY_BUT_NONE,
                end_time=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="time_tool",
                        type="tool",
                        project_name=project_name,
                        input={"timezone": "Europe/Zurich"},
                        output={"time": "09:30"},
                        metadata=ANY_DICT.containing({"events": ANY_LIST}),
                        start_time=ANY_BUT_NONE,
                        end_time=None,
                        spans=[],
                    )
                ],
            )
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert len(trace_tree.spans[0].spans[0].metadata["events"]) == 2


def test_opik_span_processor_llm_response_is_preserved_when_span_ends_with_error(
    fake_backend_without_batching,
    flush_tracker,
):
    project_name = "agentspec-llm-error-test"
    llm_config = OpenAiConfig(name="demo-model", model_id=llm_constants.OPENAI_GPT_NANO)
    span_processor = OpikSpanProcessor(
        project_name=project_name,
        mask_sensitive_information=False,
    )

    with Trace(name="AgentSpec workflow", span_processors=[span_processor]):
        with pytest.raises(RuntimeError, match="llm failed after response"):
            with LlmGenerationSpan(
                name="llm_generation",
                llm_config=llm_config,
                events=[
                    LlmGenerationRequest(
                        llm_config=llm_config,
                        prompt=[
                            Message(
                                content="my prompt",
                                role="system",
                                sender="me",
                            )
                        ],
                        tools=[],
                        request_id="llm-request",
                    ),
                    LlmGenerationResponse(
                        llm_config=llm_config,
                        content="sunny",
                        request_id="llm-request",
                        input_tokens=11,
                        output_tokens=4,
                    ),
                ],
            ):
                raise RuntimeError("llm failed after response")

    flush_tracker()

    assert len(fake_backend_without_batching.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="AgentSpec workflow",
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RootSpan",
                type="general",
                project_name=project_name,
                input={},
                output=None,
                metadata=ANY_DICT.containing({"events": []}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="llm_generation",
                        type="llm",
                        project_name=project_name,
                        model="demo-model",
                        input=ANY_DICT.containing(
                            {
                                "request_id": "llm-request",
                                "prompt": [
                                    {
                                        "id": None,
                                        "content": "my prompt",
                                        "role": "system",
                                        "sender": "me",
                                    }
                                ],
                            }
                        ),
                        output={
                            "response": "sunny",
                            "tool_calls": [],
                            "completion_id": None,
                        },
                        usage=ANY_DICT.containing(
                            {
                                "prompt_tokens": 11,
                                "completion_tokens": 4,
                                "total_tokens": 15,
                            }
                        ),
                        error_info={
                            "exception_type": "RuntimeError",
                            "message": "llm failed after response",
                            "traceback": ANY_STRING.containing(
                                "RuntimeError: llm failed after response"
                            ),
                        },
                        metadata=ANY_DICT.containing({"events": ANY_LIST}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            ),
        ],
    )

    trace_tree = fake_backend_without_batching.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert len(trace_tree.spans[0].spans[0].metadata["events"]) == 3


def test_agentspec_instrumentor_active_trace_exists_raises_value_error():
    instrumentor = AgentSpecInstrumentor()

    with Trace(name="existing trace"):
        with pytest.raises(
            ValueError,
            match="Agent Spec Trace already active",
        ):
            instrumentor.instrument(project_name="agentspec-instrumentor-test")
