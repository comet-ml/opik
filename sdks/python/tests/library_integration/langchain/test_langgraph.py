from typing import Dict, Any, Annotated
from pydantic import BaseModel

from langchain_core.messages import HumanMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph import message as langgraph_message
from typing_extensions import TypedDict
import langchain_openai

import opik
from opik.integrations.langchain import OpikTracer
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
