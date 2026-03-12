from typing import Dict, Any, Annotated, Optional, Literal

import langchain_openai
import pytest
from langchain_core.messages import HumanMessage, AIMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph import message as langgraph_message
from langgraph.types import interrupt, Command
from pydantic import BaseModel
from typing_extensions import TypedDict

import opik
from opik import jsonable_encoder, context_storage
from opik.api_objects import opik_client
from opik.api_objects import span, trace
from opik.integrations.langchain import (
    OpikTracer,
    extract_current_langgraph_span_data,
    LANGGRAPH_INTERRUPT_OUTPUT_KEY,
    LANGGRAPH_RESUME_INPUT_KEY,
    LANGGRAPH_INTERRUPT_METADATA_KEY,
)
from opik.types import DistributedTraceHeadersDict
from .constants import (
    EXPECTED_FULL_OPENAI_USAGE_LOGGED_FORMAT,
    OPENAI_MODEL_FOR_TESTS,
)
from ...testlib import (
    ANY_BUT_NONE,
    ANY_LIST,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_langgraph__happyflow(
    fake_backend,
):
    class State(BaseModel):
        message: str
        response: str = ""

    @opik.track(type="tool")
    def greeting_text_creator(input: str) -> str:
        if "hello" in input.lower() or "hi" in input.lower():
            response = "Hello! How can I help you today?"
        else:
            response = "Greetings! Is there something I can assist you with?"

        return response

    def respond_to_greeting(state: State) -> Dict[str, Any]:
        greeting = state.message
        response = greeting_text_creator(greeting)
        return {"message": state.message, "response": response}

    builder = StateGraph(State)
    builder.add_node("respond_to_greeting", respond_to_greeting)
    builder.add_edge(START, "respond_to_greeting")
    builder.add_edge("respond_to_greeting", END)

    graph = builder.compile()

    callback = OpikTracer(
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )

    initial_state = {
        "message": "Hi there!",
        "response": "",
    }
    result = graph.invoke(initial_state, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=initial_state,
        output=result,
        tags=["tag1", "tag2"],
        metadata={
            "a": "b",
            "created_from": "langchain",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="respond_to_greeting",
                input={"input": initial_state},
                output=result,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="tool",
                        name="greeting_text_creator",
                        input={"input": initial_state["message"]},
                        output={"output": result["response"]},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__invoked_from_tracked_function__langgraph_span_is_kept(
    fake_backend,
):
    """Test that LangGraph happy flow works correctly when invoked from a tracked function.

    When LangGraph is invoked from a tracked function, the LangGraph span should be kept
    (not skipped) and attached to the parent span.
    """

    class State(BaseModel):
        message: str
        response: str = ""

    @opik.track(type="tool")
    def greeting_text_creator(input: str) -> str:
        if "hello" in input.lower() or "hi" in input.lower():
            response = "Hello! How can I help you today?"
        else:
            response = "Greetings! Is there something I can assist you with?"

        return response

    def respond_to_greeting(state: State) -> Dict[str, Any]:
        greeting = state.message
        response = greeting_text_creator(greeting)
        return {"message": state.message, "response": response}

    builder = StateGraph(State)
    builder.add_node("respond_to_greeting", respond_to_greeting)
    builder.add_edge(START, "respond_to_greeting")
    builder.add_edge("respond_to_greeting", END)

    graph = builder.compile()

    callback = OpikTracer(
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )

    @opik.track(name="invoke_graph")
    def invoke_graph_from_tracked_function(
        input_data: Dict[str, Any],
    ) -> Dict[str, Any]:
        return graph.invoke(input_data, config={"callbacks": [callback]})

    initial_state = {
        "message": "Hi there!",
        "response": "",
    }
    result = invoke_graph_from_tracked_function(initial_state)

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="invoke_graph",
        input={"input_data": initial_state},
        output=result,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="invoke_graph",
                input={"input_data": initial_state},
                output=result,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="LangGraph",
                        input=initial_state,
                        output=result,
                        tags=["tag1", "tag2"],
                        metadata={
                            "a": "b",
                            "created_from": "langchain",
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="respond_to_greeting",
                                input={"input": initial_state},
                                output=result,
                                metadata=ANY_DICT,
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        type="tool",
                                        name="greeting_text_creator",
                                        input={"input": initial_state["message"]},
                                        output={"output": result["response"]},
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        spans=[],
                                    ),
                                ],
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert (
        len(callback.created_traces()) == 0
    )  # No new trace created, attached to existing trace
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__ChatOpenAI_used_in_the_node_with_config__langchain_looses_parent_child_relationship_for_Run__but_opik_tracer_restores_it(
    fake_backend,
):
    class State(TypedDict):
        # Messages have the type "list". The `add_messages` function
        # in the annotation defines how this state key should be updated
        # (in this case, it appends messages to the list, rather than overwriting them)
        messages: Annotated[list, langgraph_message.add_messages]

    opik_tracer = OpikTracer()
    llm = langchain_openai.ChatOpenAI(
        model=OPENAI_MODEL_FOR_TESTS,
    )

    graph_builder = StateGraph(State)

    def chatbot_with_config_passed(state: State):
        """
        If we pass config with OpikTracer callback in invoke method, Langchain will lose
        parent-child relationship for Run (it will work but will be considered a root span).
        OpikTracer restores it via its context.
        """
        config = {"callbacks": [opik_tracer]}

        return {"messages": [llm.invoke(state["messages"], config=config)]}

    graph_builder.add_node("chatbot_with_config_passed", chatbot_with_config_passed)
    graph_builder.add_edge(START, "chatbot_with_config_passed")
    graph_builder.add_edge("chatbot_with_config_passed", END)

    graph = graph_builder.compile()
    input = {
        "messages": [HumanMessage(content="Tell a short joke?", id="test-message-id")]
    }
    _ = graph.invoke(
        input=input,
        config={"callbacks": [opik_tracer]},
    )

    expected_input = jsonable_encoder.encode(input)
    opik_tracer.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=expected_input,
        output=ANY_DICT.containing({"messages": ANY_LIST}),
        metadata=ANY_DICT.containing({"created_from": "langchain"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chatbot_with_config_passed",
                input=expected_input,
                output=ANY_DICT.containing({"messages": ANY_LIST}),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="ChatOpenAI",
                        input={"messages": ANY_LIST},
                        output=ANY_DICT.containing({"generations": ANY_LIST}),
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=EXPECTED_FULL_OPENAI_USAGE_LOGGED_FORMAT,
                        model=ANY_STRING.starting_with(OPENAI_MODEL_FOR_TESTS),
                        provider="openai",
                        type="llm",
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__ChatOpenAI_used_in_the_node_with_config__langchain_looses_parent_child_relationship_for_Run__invoked_from_opik_tracked_function__creates_nested_trace_structure(
    fake_backend,
):
    class State(TypedDict):
        # Messages have the type "list". The `add_messages` function
        # in the annotation defines how this state key should be updated
        # (in this case, it appends messages to the list, rather than overwriting them)
        messages: Annotated[list, langgraph_message.add_messages]

    opik_tracer = OpikTracer()
    llm = langchain_openai.ChatOpenAI(
        model=OPENAI_MODEL_FOR_TESTS,
    )

    graph_builder = StateGraph(State)

    def chatbot_with_config_passed(state: State):
        """
        If we pass config with OpikTracer callback in invoke method, Langchain will lose
        parent-child relationship for Run (it will work but will be considered a root span).
        OpikTracer restores it via its context.
        """
        config = {"callbacks": [opik_tracer]}

        return {"messages": [llm.invoke(state["messages"], config=config)]}

    graph_builder.add_node("chatbot_with_config_passed", chatbot_with_config_passed)
    graph_builder.add_edge(START, "chatbot_with_config_passed")
    graph_builder.add_edge("chatbot_with_config_passed", END)

    graph = graph_builder.compile()

    @opik.track(name="f")
    def invoke_graph_from_tracked_function(input_data):
        return graph.invoke(
            input=input_data,
            config={"callbacks": [opik_tracer]},
        )

    input = {
        "messages": [HumanMessage(content="Tell a short joke?", id="test-message-id-2")]
    }
    result = invoke_graph_from_tracked_function(input)

    expected_input = jsonable_encoder.encode(input)
    expected_trace_output = jsonable_encoder.encode(result)
    opik_tracer.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"input_data": expected_input},
        output=expected_trace_output,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"input_data": expected_input},
                output=expected_trace_output,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="LangGraph",
                        input=expected_input,
                        output=ANY_DICT.containing({"messages": ANY_LIST}),
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="chatbot_with_config_passed",
                                input=expected_input,
                                output=ANY_DICT.containing({"messages": ANY_LIST}),
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name="ChatOpenAI",
                                        input={"messages": ANY_LIST},
                                        output=ANY_DICT.containing(
                                            {"generations": ANY_LIST}
                                        ),
                                        metadata=ANY_DICT.containing(
                                            {"created_from": "langchain"}
                                        ),
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        usage=EXPECTED_FULL_OPENAI_USAGE_LOGGED_FORMAT,
                                        model=ANY_STRING.starting_with(
                                            OPENAI_MODEL_FOR_TESTS
                                        ),
                                        provider="openai",
                                        type="llm",
                                        spans=[],
                                    ),
                                ],
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 0
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__node_returning_command__output_captured_correctly(
    fake_backend,
):
    """
    Regression test for https://github.com/comet-ml/opik/issues/3687

    Nodes returning Command objects should have their state updates captured in output.
    """
    from langgraph.types import Command

    class State(TypedDict):
        messages: Annotated[list, langgraph_message.add_messages]

    def node_a(state: State) -> Dict[str, Any]:
        return {"messages": [AIMessage(content="Node A answer")]}

    def node_b_command(state: State) -> Command[Literal["node_c"]]:
        return Command(
            update={"messages": [AIMessage(content="Node B answer")]}, goto="node_c"
        )

    def node_c(state: State) -> Dict[str, Any]:
        return {"messages": [AIMessage(content="Node C answer")]}

    graph_builder = StateGraph(State)
    graph_builder.add_node("node_a", node_a)
    graph_builder.add_node("node_b_command", node_b_command)
    graph_builder.add_node("node_c", node_c)

    graph_builder.add_edge(START, "node_a")
    graph_builder.add_edge("node_a", "node_b_command")
    graph_builder.add_edge("node_c", END)

    graph = graph_builder.compile()

    opik_tracer = OpikTracer(tags=["command-test"])
    initial_state = {"messages": []}
    result = graph.invoke(initial_state, config={"callbacks": [opik_tracer]})

    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    def find_span_by_name(spans, name):
        for span_ in spans:
            if span_.name == name:
                return span_
            if span_.spans:
                found = find_span_by_name(span_.spans, name)
                if found:
                    return found
        return None

    # Node spans are now direct children of the trace (no LangGraph span wrapper)
    node_a_span = find_span_by_name(trace_tree.spans, "node_a")
    node_b_span = find_span_by_name(trace_tree.spans, "node_b_command")
    node_c_span = find_span_by_name(trace_tree.spans, "node_c")

    assert node_a_span is not None
    assert node_b_span is not None
    assert node_c_span is not None

    assert "messages" in node_a_span.output
    assert len(node_a_span.output["messages"]) == 1
    assert "Node A answer" in str(node_a_span.output["messages"][0])

    # node_b_command returns a Command object, so output is wrapped
    # Check if the output contains either direct messages or wrapped in Command structure
    if "messages" in node_b_span.output:
        # Direct output structure
        assert len(node_b_span.output["messages"]) == 1
        assert "Node B answer" in str(node_b_span.output["messages"][0])
    elif "output" in node_b_span.output and "update" in node_b_span.output["output"]:
        # Wrapped Command structure
        assert "messages" in node_b_span.output["output"]["update"]
        assert len(node_b_span.output["output"]["update"]["messages"]) == 1
        assert "Node B answer" in str(
            node_b_span.output["output"]["update"]["messages"][0]
        )
    else:
        raise AssertionError(
            f"Unexpected output structure for node_b_span: {node_b_span.output}"
        )

    assert "messages" in node_c_span.output
    assert len(node_c_span.output["messages"]) == 1
    assert "Node C answer" in str(node_c_span.output["messages"][0])

    assert "messages" in result
    assert len(result["messages"]) == 3
    messages_content = [msg.content for msg in result["messages"]]
    assert "Node A answer" in messages_content
    assert "Node B answer" in messages_content
    assert "Node C answer" in messages_content


@pytest.mark.asyncio
async def test_extract_current_langgraph_span_data__async_langgraph_node__happyflow(
    fake_backend,
):
    """
    Test that extract_current_langgraph_span_data correctly extracts span data
    from a LangGraph runnable config in an async node context.
    """

    class State(TypedDict):
        messages: Annotated[list, langgraph_message.add_messages]
        extracted_trace_data: Dict[str, Any]

    extracted_data_store = {}

    @opik.track
    def inner_tracked_function(x):
        return x * 2

    async def async_node_with_span_extraction(state: State, config) -> Dict[str, Any]:
        """Async LangGraph node that extracts current span data."""
        # Extract span data using the helper function
        span_data = extract_current_langgraph_span_data(config)
        assert span_data is not None

        # Use the span data to propagate trace context to a tracked function
        result = inner_tracked_function(
            21, opik_distributed_trace_headers=span_data.get_distributed_trace_headers()
        )

        # Store the extracted data for verification
        extracted_data_store["span_data"] = span_data

        # Return some dummy data to continue the graph
        return {
            "messages": [{"role": "assistant", "content": "Span extraction completed"}],
            "extracted_trace_data": {
                "has_span_data": span_data is not None,
                "trace_id": span_data.trace_id,
                "span_id": span_data.id,
                "computation_result": result,
            },
        }

    # Create graph with OpikTracer
    opik_tracer = OpikTracer(tags=["span-extraction-test"])
    graph = StateGraph(State)

    graph.add_node("async_span_extractor", async_node_with_span_extraction)
    graph.add_edge(START, "async_span_extractor")
    graph.add_edge("async_span_extractor", END)

    compiled_graph = graph.compile()

    # Execute with OpikTracer in config
    initial_state = {
        "messages": [HumanMessage(content="Test span extraction")],
        "extracted_trace_data": {},
    }

    await compiled_graph.ainvoke(initial_state, config={"callbacks": [opik_tracer]})

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=ANY_DICT.containing({"messages": ANY_LIST}),
        output=ANY_DICT.containing({"messages": ANY_LIST}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        tags=["span-extraction-test"],
        metadata=ANY_DICT.containing({"created_from": "langchain"}),
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_span_extractor",
                input=ANY_DICT.containing({"messages": ANY_LIST}),
                output=ANY_DICT.containing({"messages": ANY_LIST}),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="inner_tracked_function",
                        input={"x": 21},
                        output={"output": 42},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__distributed_headers__langgraph_span_is_kept(
    fake_backend,
):
    """Test that LangGraph works correctly with distributed tracing headers.

    When LangGraph is invoked with distributed headers, the LangGraph span should be kept
    (not skipped) and should be added to the distributed trace/span.
    """
    project_name = "langgraph-integration-test--distributed-headers"
    client = opik_client.get_client_cached()

    # PREPARE DISTRIBUTED HEADERS
    trace_data = trace.TraceData(
        name="custom-distributed-headers--trace",
        input={
            "key1": 1,
            "key2": "val2",
        },
        project_name=project_name,
        tags=["tag_d1", "tag_d2"],
    )
    trace_data.init_end_time()
    client.trace(**trace_data.as_parameters)

    span_data = span.SpanData(
        trace_id=trace_data.id,
        parent_span_id=None,
        name="custom-distributed-headers--span",
        input={
            "input": "custom-distributed-headers--input",
        },
        project_name=project_name,
        tags=["tag_d3", "tag_d4"],
    )
    span_data.init_end_time().update(
        output={"output": "custom-distributed-headers--output"},
    )
    client.span(**span_data.as_parameters)

    distributed_headers = DistributedTraceHeadersDict(
        opik_trace_id=span_data.trace_id,
        opik_parent_span_id=span_data.id,
    )

    # CREATE LANGRAPH
    class State(BaseModel):
        message: str
        response: str = ""

    @opik.track(type="tool")
    def greeting_text_creator(input: str) -> str:
        if "hello" in input.lower() or "hi" in input.lower():
            response = "Hello! How can I help you today?"
        else:
            response = "Greetings! Is there something I can assist you with?"

        return response

    def respond_to_greeting(state: State) -> Dict[str, Any]:
        greeting = state.message
        response = greeting_text_creator(greeting)
        return {"message": state.message, "response": response}

    builder = StateGraph(State)
    builder.add_node("respond_to_greeting", respond_to_greeting)
    builder.add_edge(START, "respond_to_greeting")
    builder.add_edge("respond_to_greeting", END)

    graph = builder.compile()

    callback = OpikTracer(
        project_name=project_name,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        distributed_headers=distributed_headers,
    )

    initial_state = {
        "message": "Hi there!",
        "response": "",
    }
    graph.invoke(initial_state, config={"callbacks": [callback]})

    callback.flush()

    assert len(fake_backend.trace_trees) == 1
    assert (
        len(callback.created_traces()) == 0
    )  # No new trace created, attached to the distributed trace

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="custom-distributed-headers--trace",
        project_name="langgraph-integration-test--distributed-headers",
        input={"key1": 1, "key2": "val2"},
        tags=["tag_d1", "tag_d2"],
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="custom-distributed-headers--span",
                input={"input": "custom-distributed-headers--input"},
                output={"output": "custom-distributed-headers--output"},
                tags=["tag_d3", "tag_d4"],
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="langgraph-integration-test--distributed-headers",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="LangGraph",
                        input={"message": "Hi there!", "response": ""},
                        output={
                            "message": "Hi there!",
                            "response": "Hello! How can I help you today?",
                        },
                        tags=["tag1", "tag2"],
                        metadata={"a": "b", "created_from": "langchain"},
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name="langgraph-integration-test--distributed-headers",
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                start_time=ANY_BUT_NONE,
                                name="respond_to_greeting",
                                input={
                                    "input": {"message": "Hi there!", "response": ""}
                                },
                                output={
                                    "message": "Hi there!",
                                    "response": "Hello! How can I help you today?",
                                },
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
                                type="general",
                                end_time=ANY_BUT_NONE,
                                project_name="langgraph-integration-test--distributed-headers",
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        start_time=ANY_BUT_NONE,
                                        name="greeting_text_creator",
                                        input={"input": "Hi there!"},
                                        output={
                                            "output": "Hello! How can I help you today?"
                                        },
                                        type="tool",
                                        end_time=ANY_BUT_NONE,
                                        project_name="langgraph-integration-test--distributed-headers",
                                        last_updated_at=ANY_BUT_NONE,
                                    )
                                ],
                                last_updated_at=ANY_BUT_NONE,
                            )
                        ],
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__used_when_there_was_already_existing_span__langgraph_span_is_kept(
    fake_backend,
):
    class State(BaseModel):
        message: str
        response: str = ""

    @opik.track(type="tool")
    def greeting_text_creator(input: str) -> str:
        if "hello" in input.lower() or "hi" in input.lower():
            response = "Hello! How can I help you today?"
        else:
            response = "Greetings! Is there something I can assist you with?"

        return response

    def respond_to_greeting(state: State) -> Dict[str, Any]:
        greeting = state.message
        response = greeting_text_creator(greeting)
        return {"message": state.message, "response": response}

    builder = StateGraph(State)
    builder.add_node("respond_to_greeting", respond_to_greeting)
    builder.add_edge(START, "respond_to_greeting")
    builder.add_edge("respond_to_greeting", END)

    graph = builder.compile()

    # create external span
    client = opik_client.get_client_cached()
    trace_data = trace.TraceData(
        name="manually-created-trace",
        input={
            "key1": 1,
            "key2": "val2",
        },
    )
    trace_data.init_end_time()
    client.trace(**trace_data.as_parameters)

    span_data = span.SpanData(
        trace_id=trace_data.id,
        name="manually-created-span",
        input={"input": "input-of-manually-created-span"},
    )
    context_storage.add_span_data(span_data)

    # invoke graph with callback
    callback = OpikTracer(
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )
    initial_state = {
        "message": "Hi there!",
        "response": "",
    }
    graph.invoke(initial_state, config={"callbacks": [callback]})

    span_data = context_storage.pop_span_data()
    span_data.init_end_time().update(
        output={"output": "output-of-manually-created-span"}
    )
    client.span(**span_data.__dict__)

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="manually-created-trace",
        project_name="Default Project",
        input={"key1": 1, "key2": "val2"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="manually-created-span",
                input={"input": "input-of-manually-created-span"},
                output={"output": "output-of-manually-created-span"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="LangGraph",
                        input={"message": "Hi there!", "response": ""},
                        output={
                            "message": "Hi there!",
                            "response": "Hello! How can I help you today?",
                        },
                        tags=["tag1", "tag2"],
                        metadata={"a": "b", "created_from": "langchain"},
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                start_time=ANY_BUT_NONE,
                                name="respond_to_greeting",
                                input={
                                    "input": {
                                        "message": "Hi there!",
                                        "response": "",
                                    }
                                },
                                output={
                                    "message": "Hi there!",
                                    "response": "Hello! How can I help you today?",
                                },
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
                                type="general",
                                end_time=ANY_BUT_NONE,
                                project_name="Default Project",
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        start_time=ANY_BUT_NONE,
                                        name="greeting_text_creator",
                                        input={"input": "Hi there!"},
                                        output={
                                            "output": "Hello! How can I help you today?"
                                        },
                                        type="tool",
                                        end_time=ANY_BUT_NONE,
                                        project_name="Default Project",
                                        last_updated_at=ANY_BUT_NONE,
                                    )
                                ],
                                last_updated_at=ANY_BUT_NONE,
                            )
                        ],
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert len(fake_backend.trace_trees) == 1
    assert (
        len(callback.created_traces()) == 0
    )  # No new trace created, attached to the existing trace
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__used_when_there_was_already_existing_trace_without_span__langgraph_span_is_kept(
    fake_backend,
):
    class State(BaseModel):
        message: str
        response: str = ""

    @opik.track(type="tool")
    def greeting_text_creator(input: str) -> str:
        if "hello" in input.lower() or "hi" in input.lower():
            response = "Hello! How can I help you today?"
        else:
            response = "Greetings! Is there something I can assist you with?"

        return response

    def respond_to_greeting(state: State) -> Dict[str, Any]:
        greeting = state.message
        response = greeting_text_creator(greeting)
        return {"message": state.message, "response": response}

    builder = StateGraph(State)
    builder.add_node("respond_to_greeting", respond_to_greeting)
    builder.add_edge(START, "respond_to_greeting")
    builder.add_edge("respond_to_greeting", END)

    graph = builder.compile()

    # create external trace and invoke LangGraph within
    client = opik_client.get_client_cached()
    trace_data = trace.TraceData(
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
    )
    context_storage.set_trace_data(trace_data)

    # invoke graph with callback
    callback = OpikTracer(
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )
    initial_state = {
        "message": "Hi there!",
        "response": "",
    }
    graph.invoke(initial_state, config={"callbacks": [callback]})

    # Send trace data
    trace_data = context_storage.pop_trace_data()
    trace_data.init_end_time().update(
        output={"output": "output-of-manually-created-trace"}
    )
    client.trace(**trace_data.__dict__)

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="manually-created-trace",
        project_name="Default Project",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="LangGraph",
                input={"message": "Hi there!", "response": ""},
                output={
                    "message": "Hi there!",
                    "response": "Hello! How can I help you today?",
                },
                tags=["tag1", "tag2"],
                metadata={"a": "b", "created_from": "langchain"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="respond_to_greeting",
                        input={
                            "input": {
                                "message": "Hi there!",
                                "response": "",
                            }
                        },
                        output={
                            "message": "Hi there!",
                            "response": "Hello! How can I help you today?",
                        },
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                start_time=ANY_BUT_NONE,
                                name="greeting_text_creator",
                                input={"input": "Hi there!"},
                                output={"output": "Hello! How can I help you today?"},
                                type="tool",
                                end_time=ANY_BUT_NONE,
                                project_name="Default Project",
                                last_updated_at=ANY_BUT_NONE,
                            )
                        ],
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert len(fake_backend.trace_trees) == 1
    assert (
        len(callback.created_traces()) == 0
    )  # No new trace created, attached to the existing trace
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langgraph__interrupt_resume__second_trace_has_correct_input(
    fake_backend,
):
    """Test that when LangGraph uses interrupts, the second trace (after resume) has correct input.

    When a LangGraph execution is interrupted and then resumed with Command(resume=...),
    the second trace should have the resume value as input, not an empty dict.
    """

    class GraphState(TypedDict):
        question: Optional[str]
        selected_option: Optional[str]
        is_ambiguous: Optional[bool]
        options: Optional[list]
        response: Optional[str]

    def is_ambiguous(question: str) -> bool:
        """Simple check: questions with 'it', 'that', 'this' or very short are ambiguous"""
        ambiguous_words = ["it", "that", "this", "they"]
        question_lower = question.lower()
        return (
            any(word in question_lower for word in ambiguous_words)
            or len(question.split()) < 3
        )

    def check_ambiguity_node(state):
        question = state.get("question", "").strip()
        selected_option = state.get("selected_option")

        # If user already selected an option, not ambiguous anymore
        if selected_option:
            return {"is_ambiguous": False}

        # Check if question is ambiguous
        ambiguous = is_ambiguous(question)
        return {"is_ambiguous": ambiguous}

    def provide_options_node(state):
        options = [
            "Option 1: Weather information",
            "Option 2: News updates",
            "Option 3: Product recommendations",
            "Option 4: General information",
        ]
        response = "Please select one of these options:\n" + "\n".join(
            f"{i + 1}. {opt}" for i, opt in enumerate(options)
        )

        # Interrupt execution to wait for user input
        choice = interrupt(response)

        return {"options": options, "selected_option": choice}

    def handle_selection_node(state):
        selected_option = state.get("selected_option", "").strip()

        # Map selection to answer
        option_answers = {
            "1": "Here's the weather information you requested.",
            "2": "Here are the latest news updates.",
            "3": "Here are some product recommendations based on your preferences.",
            "4": "Here's the general information you asked about.",
        }

        answer = option_answers.get(selected_option, "I'll help you with that.")
        return {"response": answer}

    def decide_next_node(state):
        if state.get("is_ambiguous"):
            return "provide_options"
        else:
            return "handle_selection"

    workflow = StateGraph(GraphState)
    workflow.add_node("check_ambiguity", check_ambiguity_node)
    workflow.add_node("provide_options", provide_options_node)
    workflow.add_node("handle_selection", handle_selection_node)

    workflow.add_conditional_edges(
        "check_ambiguity",
        decide_next_node,
        {"provide_options": "provide_options", "handle_selection": "handle_selection"},
    )

    workflow.set_entry_point("check_ambiguity")
    workflow.add_edge("provide_options", "check_ambiguity")
    workflow.add_edge("handle_selection", END)

    # Compile with memory checkpoint for interrupts
    memory = MemorySaver()
    app = workflow.compile(checkpointer=memory)

    tracer = OpikTracer(graph=app.get_graph(xray=True))

    # Q1: Ambiguous question - graph will interrupt
    config = {"configurable": {"thread_id": "test-thread"}, "callbacks": [tracer]}
    initial_input = {"question": "Tell me about it"}

    # First invocation - will hit the interrupt
    first_result = app.invoke(initial_input, config=config)
    assert LANGGRAPH_INTERRUPT_OUTPUT_KEY in first_result

    tracer.flush()

    # Q2: Resume execution - will process the selection
    final_result = app.invoke(Command(resume="1"), config=config)
    assert final_result["response"] == "Here's the weather information you requested."

    tracer.flush()

    # Verify we have 2 traces (one for initial invoke, one for resume)
    assert len(fake_backend.trace_trees) == 2
    assert len(tracer.created_traces()) == 2

    # Build expected trace tree for first trace (interrupted)
    EXPECTED_FIRST_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=initial_input,
        output=ANY_DICT.containing({LANGGRAPH_INTERRUPT_OUTPUT_KEY: ANY_STRING}),
        metadata=ANY_DICT.containing(
            {"created_from": "langchain", LANGGRAPH_INTERRUPT_METADATA_KEY: True}
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=None,  # GraphInterrupt is not an error
        thread_id="test-thread",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="check_ambiguity",
                input=ANY_DICT,
                output=ANY_DICT.containing({"is_ambiguous": True}),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="decide_next_node",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        error_info=None,
                        spans=[],
                    ),
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="provide_options",
                input=ANY_DICT,
                output=ANY_DICT.containing(
                    {LANGGRAPH_INTERRUPT_OUTPUT_KEY: ANY_STRING}
                ),
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "langchain",
                        LANGGRAPH_INTERRUPT_METADATA_KEY: True,
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=[],
            ),
        ],
    )

    # Build expected trace tree for second trace (resumed)
    # When resuming, the provide_options node completes first (it was interrupted),
    # then goes back to check_ambiguity, and finally to handle_selection
    EXPECTED_SECOND_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={
            LANGGRAPH_RESUME_INPUT_KEY: "1"
        },  # Resume value should be captured as input
        output=ANY_DICT.containing(
            {"response": "Here's the weather information you requested."}
        ),
        metadata=ANY_DICT.containing({"created_from": "langchain"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=None,
        thread_id="test-thread",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="provide_options",
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=[],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="check_ambiguity",
                input=ANY_DICT,
                output=ANY_DICT.containing({"is_ambiguous": False}),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="decide_next_node",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        error_info=None,
                        spans=[],
                    ),
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="handle_selection",
                input=ANY_DICT,
                output=ANY_DICT.containing(
                    {"response": "Here's the weather information you requested."}
                ),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=None,
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_FIRST_TRACE, fake_backend.trace_trees[0])
    assert_equal(EXPECTED_SECOND_TRACE, fake_backend.trace_trees[1])
