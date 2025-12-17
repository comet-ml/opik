from typing import Dict, Any
from pydantic import BaseModel

from langgraph.graph import END, START, StateGraph

import opik
from opik.integrations.langchain import OpikTracer, track_langgraph

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_track_langgraph__happyflow__no_config_passed(
    fake_backend,
):
    """Test that track_langgraph allows invocations without passing config."""

    class State(BaseModel):
        input_text: str
        output_text: str = ""

    @opik.track(type="tool")
    def process_text(text: str) -> str:
        return f"processed_{text}"

    def processing_node(state: State) -> Dict[str, Any]:
        output = process_text(state.input_text)
        return {"input_text": state.input_text, "output_text": output}

    builder = StateGraph(State)
    builder.add_node("processing_node", processing_node)
    builder.add_edge(START, "processing_node")
    builder.add_edge("processing_node", END)

    graph = builder.compile()

    opik_tracer = OpikTracer(
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )
    tracked_graph = track_langgraph(graph, opik_tracer)

    initial_state = {
        "input_text": "test_input",
        "output_text": "",
    }

    result = tracked_graph.invoke(initial_state)
    opik_tracer.flush()

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input=initial_state,
        output=result,
        tags=["tag1", "tag2"],
        metadata=ANY_DICT.containing(
            {
                "a": "b",
                "created_from": "langchain",
                "_opik_graph_definition": ANY_DICT,
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="processing_node",
                input={"input": initial_state},
                output=result,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="tool",
                        name="process_text",
                        input={"text": "test_input"},
                        output={"output": "processed_test_input"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                    ),
                ],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


def test_track_langgraph__multiple_invocations__all_tracked(
    fake_backend,
):
    """Test that multiple invocations of a tracked graph are all tracked."""

    class State(BaseModel):
        value: int

    def multiply_by_two(state: State) -> Dict[str, Any]:
        return {"value": state.value * 2}

    builder = StateGraph(State)
    builder.add_node("multiply_by_two", multiply_by_two)
    builder.add_edge(START, "multiply_by_two")
    builder.add_edge("multiply_by_two", END)

    graph = builder.compile()
    opik_tracer = OpikTracer(project_name="test-project")
    tracked_graph = track_langgraph(graph, opik_tracer)

    result1 = tracked_graph.invoke({"value": 1})
    result2 = tracked_graph.invoke({"value": 2})
    result3 = tracked_graph.invoke({"value": 3})
    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 3

    EXPECTED_TRACE_1 = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"value": 1},
        output=result1,
        project_name="test-project",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata=ANY_DICT,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="multiply_by_two",
                input={"input": {"value": 1}},
                output=result1,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )
    assert_equal(EXPECTED_TRACE_1, fake_backend.trace_trees[0])

    EXPECTED_TRACE_2 = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"value": 2},
        output=result2,
        project_name="test-project",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata=ANY_DICT,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="multiply_by_two",
                input={"input": {"value": 2}},
                output=result2,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )
    assert_equal(EXPECTED_TRACE_2, fake_backend.trace_trees[1])

    EXPECTED_TRACE_3 = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"value": 3},
        output=result3,
        project_name="test-project",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata=ANY_DICT,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="multiply_by_two",
                input={"input": {"value": 3}},
                output=result3,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )
    assert_equal(EXPECTED_TRACE_3, fake_backend.trace_trees[2])


def test_track_langgraph__tracked_twice__invoked_once__single_trace_created(
    fake_backend,
):
    """Test that tracking the same graph twice and invoking once creates only one trace."""

    class State(BaseModel):
        value: int

    def double_value_node(state: State) -> Dict[str, Any]:
        return {"value": state.value * 2}

    builder = StateGraph(State)
    builder.add_node("double_value_node", double_value_node)
    builder.add_edge(START, "double_value_node")
    builder.add_edge("double_value_node", END)

    graph = builder.compile()

    opik_tracer = OpikTracer(project_name="duplicate-test")
    tracked_graph = track_langgraph(graph, opik_tracer)

    tracked_graph_again = track_langgraph(tracked_graph, opik_tracer)

    result = tracked_graph_again.invoke({"value": 10})
    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"value": 10},
        output=result,
        project_name="duplicate-test",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata=ANY_DICT,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="double_value_node",
                input={"input": {"value": 10}},
                output=result,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )
    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


def test_track_langgraph__invoked_from_tracked_function__proper_tracing(
    fake_backend,
):
    """Test that invoking a tracked graph from a @track-decorated function creates proper traces."""

    class State(BaseModel):
        text: str

    def uppercase_node(state: State) -> Dict[str, Any]:
        return {"text": state.text.upper()}

    builder = StateGraph(State)
    builder.add_node("uppercase_node", uppercase_node)
    builder.add_edge(START, "uppercase_node")
    builder.add_edge("uppercase_node", END)

    graph = builder.compile()
    opik_tracer = OpikTracer()
    tracked_graph = track_langgraph(graph, opik_tracer)

    @opik.track(name="outer_function")
    def invoke_graph_from_tracked_function(input_text: str):
        initial_state = {"text": input_text}
        return tracked_graph.invoke(initial_state)

    result = invoke_graph_from_tracked_function("test_input")
    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="outer_function",
        input={"input_text": "test_input"},
        output=result,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="outer_function",
                input={"input_text": "test_input"},
                output=result,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="LangGraph",
                        input={"text": "test_input"},
                        output=result,
                        metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="uppercase_node",
                                input={"input": {"text": "test_input"}},
                                output=result,
                                metadata=ANY_DICT,
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                            )
                        ],
                    )
                ],
            )
        ],
    )
    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


def test_track_langgraph__with_project_name_tags_metadata__all_applied(
    fake_backend,
):
    """Test that project_name, tags, and metadata are properly applied to traces."""

    class State(BaseModel):
        value: int

    def increment_node(state: State) -> Dict[str, Any]:
        return {"value": state.value + 1}

    builder = StateGraph(State)
    builder.add_node("increment_node", increment_node)
    builder.add_edge(START, "increment_node")
    builder.add_edge("increment_node", END)

    graph = builder.compile()
    opik_tracer = OpikTracer(
        project_name="metadata-test-project",
        tags=["test-tag-1", "test-tag-2"],
        metadata={"test_key": "test_value", "env": "test"},
    )
    tracked_graph = track_langgraph(graph, opik_tracer)

    result = tracked_graph.invoke({"value": 100})
    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"value": 100},
        output=result,
        project_name="metadata-test-project",
        tags=["test-tag-1", "test-tag-2"],
        metadata=ANY_DICT.containing(
            {
                "test_key": "test_value",
                "env": "test",
                "created_from": "langchain",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="increment_node",
                input={"input": {"value": 100}},
                output=result,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )
    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


def test_track_langgraph__graph_visualization_included_in_metadata(
    fake_backend,
):
    """Test that graph visualization is automatically extracted and included in trace metadata."""

    class State(BaseModel):
        text: str

    def append_a_node(state: State) -> Dict[str, Any]:
        return {"text": state.text + "_A"}

    def append_b_node(state: State) -> Dict[str, Any]:
        return {"text": state.text + "_B"}

    builder = StateGraph(State)
    builder.add_node("append_a_node", append_a_node)
    builder.add_node("append_b_node", append_b_node)
    builder.add_edge(START, "append_a_node")
    builder.add_edge("append_a_node", "append_b_node")
    builder.add_edge("append_b_node", END)

    graph = builder.compile()
    opik_tracer = OpikTracer()
    tracked_graph = track_langgraph(graph, opik_tracer)

    result = tracked_graph.invoke({"text": "input"})
    opik_tracer.flush()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="LangGraph",
        input={"text": "input"},
        output=result,
        metadata=ANY_DICT.containing(
            {
                "_opik_graph_definition": {
                    "format": "mermaid",
                    "data": ANY_STRING.containing("append_a_node").containing(
                        "append_b_node"
                    ),
                },
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="append_a_node",
                input={"input": {"text": "input"}},
                output=ANY_DICT,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="append_b_node",
                input={"input": {"text": "input_A"}},
                output=result,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            ),
        ],
    )
    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])
