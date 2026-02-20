from typing import Optional

import pytest
from opik import exceptions
from opik.integrations import langchain
from opik.integrations.langchain.run_parse_helpers import (
    parse_graph_interrupt_value,
    is_langgraph_parent_command,
)


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
    result = parse_graph_interrupt_value(error_traceback)
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
    result = is_langgraph_parent_command(error_traceback)
    assert result == expected, (
        f"Expected {expected!r}, got {result!r} for input: {error_traceback[:120]}"
    )
