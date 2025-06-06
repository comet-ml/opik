from typing import Dict, Any
from pydantic import BaseModel

import opik
from opik.integrations.langchain.opik_tracer import OpikTracer
from langgraph.graph import StateGraph, START, END
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)


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


def test_langgraph__happyflow(
    fake_backend,
):
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
