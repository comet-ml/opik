import copy
from typing import Any, Dict, List, Optional
from uuid import uuid4

import pytest
from langchain_core.tools import tool
from langchain_core.tracers import BaseTracer
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

from opik import exceptions
from opik.api_objects import span as span_module, trace as trace_module
from opik.integrations import langchain
from opik.integrations.langchain.opik_tracer import OpikTracer
from opik.integrations.langchain import run_parse_helpers


def test_opik_tracer__attach_span_to_parent_span__stream_restart_root(fake_backend):
    """When a parent run is a stream-restart root (in _span_data_map but NOT in
    _created_traces_data_map), trace data should be propagated to the child via
    a trace_id fallback lookup rather than raising a KeyError."""
    tracer = OpikTracer(opik_context_read_only_mode=True)

    # Simulate an original root run that created a trace
    trace_data = trace_module.TraceData(name="test-trace")
    trace_id = trace_data.id
    root_run_id = uuid4()
    tracer._created_traces_data_map[root_run_id] = trace_data

    # Simulate a stream-restart root: has a span with the same trace_id but is
    # NOT in _created_traces_data_map (the scenario that previously caused a KeyError)
    stream_restart_run_id = uuid4()
    parent_span = span_module.SpanData(trace_id=trace_id, name="stream-restart-span")
    tracer._span_data_map[stream_restart_run_id] = parent_span

    child_run_id = uuid4()
    run_dict = {
        "inputs": {"input": "hello"},
        "name": "child-span",
        "run_type": "general",
        "extra": {},
    }

    # Should not raise KeyError
    tracer._attach_span_to_parent_span(
        run_id=child_run_id,
        parent_run_id=stream_restart_run_id,
        run_dict=run_dict,
    )

    # Child should inherit the trace data via trace_id fallback lookup
    assert child_run_id in tracer._created_traces_data_map
    assert tracer._created_traces_data_map[child_run_id] is trace_data


def test_opik_tracer__init_validation():
    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(thread_id=1)

    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(project_name=1)

    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(tags=1)

    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(tags={"key": 1})

    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(metadata=1)

    with pytest.raises(exceptions.ValidationError):
        langchain.OpikTracer(metadata=[1])


@pytest.mark.parametrize(
    "error_traceback,expected",
    [
        # Basic string values with double quotes
        (
            'GraphInterrupt(Interrupt(value="test_value"))',
            "test_value",
        ),
        (
            'Some error\nGraphInterrupt(Interrupt(value="hello world"))\nMore text',
            "hello world",
        ),
        # Basic string values with single quotes
        (
            "GraphInterrupt(Interrupt(value='test_value'))",
            "test_value",
        ),
        (
            "GraphInterrupt(Interrupt(value='hello world'))",
            "hello world",
        ),
        # String values without quotes
        (
            "GraphInterrupt(Interrupt(value=test_value))",
            "test_value",
        ),
        (
            "GraphInterrupt(Interrupt(value=123))",
            "123",
        ),
        # Numeric values
        (
            "GraphInterrupt(Interrupt(value=42))",
            "42",
        ),
        (
            "GraphInterrupt(Interrupt(value=-123))",
            "-123",
        ),
        (
            "GraphInterrupt(Interrupt(value=3.14))",
            "3.14",
        ),
        # Boolean values
        (
            "GraphInterrupt(Interrupt(value=True))",
            "True",
        ),
        (
            "GraphInterrupt(Interrupt(value=False))",
            "False",
        ),
        # None value
        (
            "GraphInterrupt(Interrupt(value=None))",
            "None",
        ),
        # Empty string
        (
            'GraphInterrupt(Interrupt(value=""))',
            "",
        ),
        (
            "GraphInterrupt(Interrupt(value=''))",
            "",
        ),
        # String with special characters
        (
            'GraphInterrupt(Interrupt(value="hello\\nworld"))',
            "hello\nworld",
        ),
        (
            'GraphInterrupt(Interrupt(value="path/to/file"))',
            "path/to/file",
        ),
        # String with escaped quotes
        (
            'GraphInterrupt(Interrupt(value="test\\"value"))',
            'test"value',
        ),
        (
            "GraphInterrupt(Interrupt(value='test\\'value'))",
            "test'value",
        ),
        # List values
        (
            "GraphInterrupt(Interrupt(value=[1, 2, 3]))",
            "[1, 2, 3]",
        ),
        (
            'GraphInterrupt(Interrupt(value=["a", "b", "c"]))',
            '["a", "b", "c"]',
        ),
        # Dictionary values
        (
            'GraphInterrupt(Interrupt(value={"key": "value"}))',
            '{"key": "value"}',
        ),
        (
            "GraphInterrupt(Interrupt(value={'a': 1, 'b': 2}))",
            "{'a': 1, 'b': 2}",
        ),
        # Nested structures
        (
            'GraphInterrupt(Interrupt(value={"nested": [1, 2, {"inner": "value"}]}))',
            '{"nested": [1, 2, {"inner": "value"}]}',
        ),
        (
            "GraphInterrupt(Interrupt(value=[[1, 2], [3, 4]]))",
            "[[1, 2], [3, 4]]",
        ),
        # Values with commas
        (
            'GraphInterrupt(Interrupt(value="hello, world"))',
            "hello, world",
        ),
        (
            'GraphInterrupt(Interrupt(value={"a": 1, "b": 2}))',
            '{"a": 1, "b": 2}',
        ),
        # Values with nested parentheses
        (
            "GraphInterrupt(Interrupt(value=func(arg1, arg2)))",
            "func(arg1, arg2)",
        ),
        (
            'GraphInterrupt(Interrupt(value="test(value)"))',
            "test(value)",
        ),
        # Complex nested structures
        (
            'GraphInterrupt(Interrupt(value={"list": [1, (2, 3), 4], "dict": {"nested": "value"}}))',
            '{"list": [1, (2, 3), 4], "dict": {"nested": "value"}}',
        ),
        # Multi-line traceback (matches first occurrence)
        (
            'Traceback (most recent call last):\n  File "test.py", line 1\n    GraphInterrupt(Interrupt(value="test"))\nValueError: GraphInterrupt(Interrupt(value="test_value"))',
            "test",
        ),
        # Value with whitespace
        (
            'GraphInterrupt(Interrupt(value="  test  "))',
            "  test  ",
        ),
        (
            "GraphInterrupt(Interrupt(value=  test_value  ))",
            "test_value",
        ),
        # NodeInterrupt (deprecated subclass of GraphInterrupt) - repr uses a list, not tuple
        (
            "NodeInterrupt([Interrupt(value='hello')])",
            "hello",
        ),
        (
            'NodeInterrupt([Interrupt(value="review this PR", id="abc123")])',
            "review this PR",
        ),
        (
            "NodeInterrupt([Interrupt(value=42)])",
            "42",
        ),
        # Edge cases: no match
        (
            "Some random error message",
            None,
        ),
        (
            "",
            None,
        ),
        (
            'Interrupt(value="test")',
            None,
        ),
        (
            'GraphInterrupt(value="test")',
            None,
        ),
        # Malformed traceback (missing closing paren - extracts partial value)
        (
            'GraphInterrupt(Interrupt(value="test"',
            '"test',
        ),
        # Value with newlines in string
        (
            'GraphInterrupt(Interrupt(value="line1\\nline2"))',
            "line1\nline2",
        ),
        # Value with tabs
        (
            'GraphInterrupt(Interrupt(value="hello\\tworld"))',
            "hello\tworld",
        ),
        # Tuple value
        (
            "GraphInterrupt(Interrupt(value=(1, 2, 3)))",
            "(1, 2, 3)",
        ),
        # Value with function call
        (
            'GraphInterrupt(Interrupt(value=get_value("param")))',
            'get_value("param")',
        ),
        # Value with nested quotes
        (
            "GraphInterrupt(Interrupt(value=\"He said 'hello'\"))",
            "He said 'hello'",
        ),
        (
            "GraphInterrupt(Interrupt(value='He said \"hello\"'))",
            'He said "hello"',
        ),
        # Value with carriage return
        (
            'GraphInterrupt(Interrupt(value="line1\\rline2"))',
            "line1\rline2",
        ),
        # Value with backslash
        (
            'GraphInterrupt(Interrupt(value="path\\\\to\\\\file"))',
            "path\\to\\file",
        ),
        # Value with multiple escape sequences
        (
            'GraphInterrupt(Interrupt(value="line1\\nline2\\tindented\\rcarriage"))',
            "line1\nline2\tindented\rcarriage",
        ),
        # Value with unicode escape (\u0020 -> space)
        (
            'GraphInterrupt(Interrupt(value="hello\\u0020world"))',
            "hello world",
        ),
        # Value with hex escape (\x20 -> space)
        (
            'GraphInterrupt(Interrupt(value="test\\x20value"))',
            "test value",
        ),
        # Value with bell character
        (
            'GraphInterrupt(Interrupt(value="alert\\a"))',
            "alert\a",
        ),
        # Value with form feed
        (
            'GraphInterrupt(Interrupt(value="page\\fbreak"))',
            "page\fbreak",
        ),
        # Value with vertical tab
        (
            'GraphInterrupt(Interrupt(value="vertical\\vtab"))',
            "vertical\vtab",
        ),
        # Value with backspace
        (
            'GraphInterrupt(Interrupt(value="back\\bspace"))',
            "back\bspace",
        ),
        # Mixed escape sequences and regular text
        (
            'GraphInterrupt(Interrupt(value="Start\\n\\tIndented line\\n\\tAnother indented\\nEnd"))',
            "Start\n\tIndented line\n\tAnother indented\nEnd",
        ),
    ],
)
def test_parse_graph_interrupt_value(error_traceback: str, expected: Optional[str]):
    """Test parse_graph_interrupt_value with various input formats."""
    result = run_parse_helpers.parse_graph_interrupt_value(error_traceback)
    assert result == expected, (
        f"Expected {expected!r}, got {result!r} for input: {error_traceback[:100]}"
    )


@pytest.mark.parametrize(
    "error_traceback,expected",
    [
        # Detection via repr prefix (from LangChain run.error = repr(exception))
        (
            "ParentCommand(Command(graph='__parent__', goto=[Send(node='some_agent', arg={})]))",
            True,
        ),
        # Detection via a fully qualified class name in traceback
        (
            "Traceback (most recent call last):\n  File 'test.py', line 1\nlanggraph.errors.ParentCommand: Command(graph='__parent__', goto=[])",
            True,
        ),
        # Full error string matching the pattern reported by users
        (
            "ParentCommand(Command(graph='__parent__', goto=[Send(node='jira_agent', arg={'messages': [], 'remaining_steps': 9999})]))Traceback (most recent call last):\n\n  File \"langgraph/_internal/_runnable.py\", line 711, in ainvoke\n    input = await step.ainvoke(input, config)\n\nlanggraph.errors.ParentCommand: Command(graph='__parent__', goto=[])",
            True,
        ),
        # Not a ParentCommand - regular error
        (
            "ValueError: something went wrong",
            False,
        ),
        # Not a ParentCommand - GraphInterrupt
        (
            "GraphInterrupt(Interrupt(value='test'))",
            False,
        ),
        # Not a ParentCommand - empty string
        (
            "",
            False,
        ),
        # Not a ParentCommand - partial match (must start with ParentCommand or contain FQCN)
        (
            "SomeOtherParentCommand(foo)",
            False,
        ),
        # Not a ParentCommand - similar but different class
        (
            "Command(graph='__parent__', goto=[])",
            False,
        ),
    ],
)
def test_is_langgraph_parent_command(error_traceback: str, expected: bool):
    """Test is_langgraph_parent_command with various input formats."""
    result = run_parse_helpers.is_langgraph_parent_command(error_traceback)
    assert result == expected, (
        f"Expected {expected!r}, got {result!r} for input: {error_traceback[:120]}"
    )


class _RunDictRecorder(BaseTracer):
    """Captures each run's ``run.dict()`` — the exact input OpikTracer feeds the classifier.

    Used to drive the classifier tests off real LangGraph runs instead of hand-built dicts,
    so they break if LangGraph's run/metadata shape ever changes.
    """

    def __init__(self):
        super().__init__()
        self.run_dicts: List[Dict[str, Any]] = []

    def _persist_run(self, run) -> None:
        pass

    def _start_trace(self, run) -> None:
        super()._start_trace(run)
        self.run_dicts.append(run.dict())


@pytest.fixture(scope="module")
def langgraph_run_dicts() -> Dict[str, Dict[str, Any]]:
    """Real run_dicts captured from a no-LLM LangGraph run, keyed by span name.

    The graph is shaped to surface every classification case the library actually emits:
    the root graph run (no langgraph metadata), node boundaries, a conditional-edge router
    (framework plumbing), and a tool run (exercises the run_type guard) — all without any
    LLM call, so the suite stays fast and deterministic.
    """

    @tool
    def get_weather(city: str) -> str:
        """Return the weather for a given city."""
        return f"sunny in {city}"

    class State(TypedDict):
        city: str
        route: Optional[str]

    def classify(state: State, config) -> Dict[str, Any]:
        # Invoke a real tool with the propagated config so it surfaces as a tool run.
        get_weather.invoke({"city": state["city"]}, config=config)
        return {"route": "known"}

    def route(state: State) -> str:
        return "respond"

    def respond(state: State) -> Dict[str, Any]:
        return {"city": state["city"]}

    builder = StateGraph(State)
    builder.add_node("classify", classify)
    builder.add_node("respond", respond)
    builder.add_edge(START, "classify")
    builder.add_conditional_edges("classify", route, {"respond": "respond"})
    builder.add_edge("respond", END)
    graph = builder.compile()

    recorder = _RunDictRecorder()
    graph.invoke({"city": "Kyiv", "route": None}, config={"callbacks": [recorder]})

    return {run_dict["name"]: run_dict for run_dict in recorder.run_dicts}


@pytest.mark.parametrize(
    "span_name, expected_internal",
    [
        # Root graph run carries no langgraph_* metadata -> out of scope.
        ("LangGraph", False),
        # Node boundaries (run name == langgraph_node) are the meaningful spans.
        ("classify", False),
        ("respond", False),
        # A tool run keeps despite name != node, because the run_type guard wins.
        ("get_weather", False),
        # The conditional-edge router is framework plumbing (name != langgraph_node).
        ("route", True),
    ],
)
def test_is_internal_langgraph_run__real_langgraph_runs(
    langgraph_run_dicts, span_name, expected_internal
):
    run_dict = langgraph_run_dicts[span_name]
    assert run_parse_helpers.is_internal_langgraph_run(run_dict) is expected_internal


def test_get_run_metadata__real_internal_run__tagged_with_opik_category(
    langgraph_run_dicts,
):
    metadata = run_parse_helpers.get_run_metadata(langgraph_run_dicts["route"])

    assert metadata["_opik"] == {"category": "internal"}


@pytest.mark.parametrize("span_name", ["classify", "get_weather"])
def test_get_run_metadata__real_meaningful_run__not_tagged(
    langgraph_run_dicts, span_name
):
    metadata = run_parse_helpers.get_run_metadata(langgraph_run_dicts[span_name])

    assert "_opik" not in metadata


def test_get_run_metadata__existing_opik_dict__extended_not_replaced(
    langgraph_run_dicts,
):
    run_dict = copy.deepcopy(langgraph_run_dicts["route"])
    run_dict["extra"]["metadata"]["_opik"] = {"existing": "kept"}

    metadata = run_parse_helpers.get_run_metadata(run_dict)

    assert metadata["_opik"] == {
        "existing": "kept",
        "category": "internal",
    }


def test_get_run_metadata__non_dict_opik_value__coerced_then_tagged(
    langgraph_run_dicts,
):
    # "_opik" is reserved; a non-dict value there must be overwritten so the tag is still
    # written and the span stays matchable by the UI's hide logic.
    run_dict = copy.deepcopy(langgraph_run_dicts["route"])
    run_dict["extra"]["metadata"]["_opik"] = "hijacked"

    metadata = run_parse_helpers.get_run_metadata(run_dict)

    assert metadata["_opik"] == {"category": "internal"}


@pytest.mark.parametrize(
    "run_dict, expected",
    [
        # Graph entry/exit markers: name == langgraph_node, so only the "__" rule flags
        # them. They don't surface as tracer runs in the graph above, hence explicit input.
        (
            {
                "run_type": "chain",
                "name": "__start__",
                "extra": {"metadata": {"langgraph_node": "__start__"}},
            },
            True,
        ),
        (
            {
                "run_type": "chain",
                "name": "__end__",
                "extra": {"metadata": {"langgraph_node": "__end__"}},
            },
            True,
        ),
        # Defensive: malformed run_dicts a healthy run never produces must not raise or
        # be mis-flagged.
        ({"run_type": "chain", "name": "x", "extra": "not-a-dict"}, False),
        ({"run_type": "chain", "name": "x", "extra": {"metadata": []}}, False),
        (
            {
                "run_type": "chain",
                "name": None,
                "extra": {"metadata": {"langgraph_step": 1}},
            },
            False,
        ),
    ],
)
def test_is_internal_langgraph_run__markers_and_malformed_inputs(run_dict, expected):
    assert run_parse_helpers.is_internal_langgraph_run(run_dict) is expected
