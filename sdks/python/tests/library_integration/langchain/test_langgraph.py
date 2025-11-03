from typing import Dict, Any, Annotated
from pydantic import BaseModel

from langchain_core.messages import HumanMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph import message as langgraph_message
from typing_extensions import TypedDict
import langchain_openai

import opik
from opik.integrations.langchain import OpikTracer, extract_current_langgraph_span_data
from opik import jsonable_encoder

from ...testlib import (
    ANY_BUT_NONE,
    ANY_LIST,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)
from .constants import (
    EXPECTED_FULL_OPENAI_USAGE_LOGGED_FORMAT,
    OPENAI_MODEL_FOR_TESTS,
)

import pytest


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
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
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
    input = {"messages": [HumanMessage(content="Tell a short joke?")]}
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
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="ChatOpenAI",
                                input={"messages": ANY_LIST},
                                output=ANY_DICT.containing({"generations": ANY_LIST}),
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
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

    input = {"messages": [HumanMessage(content="Tell a short joke?")]}
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
    from typing import Literal
    from langchain_core.messages import AIMessage
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
        for span in spans:
            if span.name == name:
                return span
            if span.spans:
                found = find_span_by_name(span.spans, name)
                if found:
                    return found
        return None

    root_span = trace_tree.spans[0]
    node_a_span = find_span_by_name(root_span.spans, "node_a")
    node_b_span = find_span_by_name(root_span.spans, "node_b_command")
    node_c_span = find_span_by_name(root_span.spans, "node_c")

    assert node_a_span is not None
    assert node_b_span is not None
    assert node_c_span is not None

    assert "messages" in node_a_span.output
    assert len(node_a_span.output["messages"]) == 1
    assert "Node A answer" in str(node_a_span.output["messages"][0])

    assert "messages" in node_b_span.output
    assert len(node_b_span.output["messages"]) == 1
    assert "Node B answer" in str(node_b_span.output["messages"][0])

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
                name="LangGraph",
                input=ANY_DICT.containing({"messages": ANY_LIST}),
                output=ANY_DICT.containing({"messages": ANY_LIST}),
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                tags=["span-extraction-test"],
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
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
            ),
        ],
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert len(opik_tracer.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
