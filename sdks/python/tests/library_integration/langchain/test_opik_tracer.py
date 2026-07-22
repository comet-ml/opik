import copy
from typing import Any, Dict, List, Optional

import pytest
from langchain_core.language_models import fake
from langchain_core.prompts import PromptTemplate
from langchain_core.tools import tool
from langchain_core.tracers import BaseTracer
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

import opik
from opik import exceptions
from opik.integrations import langchain
from opik.integrations.langchain.opik_tracer import OpikTracer
from opik.integrations.langchain import run_parse_helpers


def test_opik_tracer__reused_across_invocations__each_call_logs_a_complete_trace(
    fake_backend,
):
    """A long-lived tracer reused across invocations must log a complete, correct
    trace for every call, with no state bleeding between runs. (The deterministic
    proof that per-run state is released is in the RunStateStore unit tests.)"""
    llm = fake.FakeListLLM(responses=["ok"] * 6)
    prompt = PromptTemplate(input_variables=["n"], template="Say something about {n}.")
    chain = prompt | llm

    tracer = OpikTracer()

    for n in range(5):
        chain.invoke({"n": str(n)}, config={"callbacks": [tracer]})

    tracer.flush()

    assert len(fake_backend.trace_trees) == 5
    assert len(tracer.created_traces()) == 5

    # Each invocation is logged as its own complete trace - inputs are not shared
    # or overwritten across runs, and every trace keeps its full span structure.
    assert {tree.input["n"] for tree in fake_backend.trace_trees} == {
        "0",
        "1",
        "2",
        "3",
        "4",
    }
    for trace_tree in fake_backend.trace_trees:
        assert trace_tree.name == "RunnableSequence"
        assert trace_tree.output is not None
        assert trace_tree.end_time is not None
        assert {span.name for span in trace_tree.spans} == {
            "PromptTemplate",
            "FakeListLLM",
        }


def test_opik_tracer__nested_under_tracked_function__spans_logged(fake_backend):
    """When the tracer runs under an externally created trace/span (an @track
    function), the LangChain root run is a real span whose own end callback fires
    after _persist_run. Its state must be released only after that span's output
    has been recorded, so the nested spans are logged intact on every invocation."""
    llm = fake.FakeListLLM(responses=["ok"] * 6)
    prompt = PromptTemplate(input_variables=["n"], template="Say something about {n}.")
    chain = prompt | llm

    tracer = OpikTracer()

    @opik.track
    def run_chain(n: int) -> str:
        chain.invoke({"n": str(n)}, config={"callbacks": [tracer]})
        return "done"

    for n in range(3):
        run_chain(n)

    opik.flush_tracker()

    # The chain's spans were attached to each tracked-function trace, not dropped.
    assert len(fake_backend.trace_trees) == 3
    for trace_tree in fake_backend.trace_trees:
        chain_span = trace_tree.spans[0].spans[0]
        assert chain_span.name == "RunnableSequence"
        assert chain_span.output is not None
        assert chain_span.end_time is not None
        assert {span.name for span in chain_span.spans} == {
            "PromptTemplate",
            "FakeListLLM",
        }


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


def test_get_run_metadata__real_internal_run__tagged_with_opik_is_internal(
    langgraph_run_dicts,
):
    metadata = run_parse_helpers.get_run_metadata(langgraph_run_dicts["route"])

    assert metadata["_opik"] == {"is_internal": True}


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
        "is_internal": True,
    }


def test_get_run_metadata__non_dict_opik_value__coerced_then_tagged(
    langgraph_run_dicts,
):
    # "_opik" is reserved; a non-dict value there must be overwritten so the tag is still
    # written and the span stays matchable by the UI's hide logic.
    run_dict = copy.deepcopy(langgraph_run_dicts["route"])
    run_dict["extra"]["metadata"]["_opik"] = "hijacked"

    metadata = run_parse_helpers.get_run_metadata(run_dict)

    assert metadata["_opik"] == {"is_internal": True}


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
