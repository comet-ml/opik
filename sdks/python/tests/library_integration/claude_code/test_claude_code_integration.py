from typing import AsyncGenerator, List, Any
import asyncio

from opik.integrations.claude_code import (
    track_claude_code,
    track_claude_code_with_options,
)


# Mock Claude Code SDK classes for testing
class MockTextBlock:
    def __init__(self, text: str):
        self.text = text


class MockAssistantMessage:
    def __init__(self, content: List[MockTextBlock]):
        self.content = content


class MockResultMessage:
    def __init__(self, total_cost_usd: float = 0.0):
        self.total_cost_usd = total_cost_usd


class MockClaudeCodeOptions:
    def __init__(
        self,
        system_prompt: str = None,
        allowed_tools: List[str] = None,
        max_turns: int = None,
        add_dirs: List[str] = None,
        continue_conversation: bool = None,
        disallowed_tools: List[str] = None,
        extra_args: List[str] = None,
        max_thinking_tokens: int = None,
        mcp_servers: List[str] = None,
    ):
        self.system_prompt = system_prompt
        self.allowed_tools = allowed_tools or []
        self.max_turns = max_turns
        self.add_dirs = add_dirs or []
        self.continue_conversation = continue_conversation
        self.disallowed_tools = disallowed_tools or []
        self.extra_args = extra_args or []
        self.max_thinking_tokens = max_thinking_tokens
        self.mcp_servers = mcp_servers or []

    def __str__(self):
        return f"ClaudeCodeOptions(system_prompt={self.system_prompt}, allowed_tools={self.allowed_tools}, max_turns={self.max_turns})"

    def __repr__(self):
        return self.__str__()


# Mock Claude Code query functions
async def mock_query_basic(prompt: str, options=None) -> AsyncGenerator[Any, None]:
    """Mock Claude Code query function - basic response."""
    yield MockAssistantMessage(
        [MockTextBlock("This is a mocked assistant response for creating files.")]
    )
    yield MockResultMessage(total_cost_usd=0.001)


async def mock_query_with_tools(prompt: str, options=None) -> AsyncGenerator[Any, None]:
    """Mock Claude Code query function - with tools and multiple responses."""
    yield MockAssistantMessage([MockTextBlock("I'll help you create that file.")])
    yield MockAssistantMessage(
        [MockTextBlock("File created successfully with the content 'Hello, World!'.")]
    )
    yield MockResultMessage(total_cost_usd=0.002)


OPIK_E2E_TESTS_PROJECT_NAME = "Default Project"


def test_track_claude_code__basic_query__happyflow(fake_backend):
    """Test basic Claude Code query tracking creates proper traces and spans."""
    tracked_query = track_claude_code(mock_query_basic)

    async def run_query():
        async for message in tracked_query("Create a file called hello.txt"):
            pass  # Just consume the messages

    # Execute the async function
    asyncio.run(run_query())

    # Flush the tracker to ensure all data is processed
    from opik.decorator import tracker

    tracker.flush_tracker()

    # Should have 1 trace with multiple spans:
    # - 1 parent span for the entire query
    # - Child spans for each message (1 assistant + 1 result)
    assert (
        len(fake_backend.trace_trees) == 1
    ), f"Expected 1 trace tree, got {len(fake_backend.trace_trees)}"

    trace = fake_backend.trace_trees[0]

    # Verify we got the expected messages in the conversation flow
    conversation_flow = trace.output["conversation_flow"]
    assert len(conversation_flow) == 2
    assert conversation_flow[0]["type"] == "mockassistantmessage"
    assert conversation_flow[1]["type"] == "mockresultmessage"

    # Verify trace structure
    assert trace.name == "claude_code_query"
    assert trace.input == {"args": ["Create a file called hello.txt"], "kwargs": {}}

    # Should have conversation flow in output
    assert "conversation_flow" in trace.output
    assert trace.output["result_info"]["total_messages_count"] == 2
    assert trace.output["result_info"]["total_cost_usd"] == 0.001

    # Should have 1 main span created by @track decorator
    assert len(trace.spans) == 1, f"Expected 1 main span, got {len(trace.spans)}"

    main_span = trace.spans[0]
    assert main_span.name == "claude_code_query"

    # The main span should have 2 child spans - one for assistant message, one for result
    assert (
        len(main_span.spans) == 2
    ), f"Expected 2 child spans, got {len(main_span.spans)}"

    # Find assistant response span
    assistant_spans = [
        span for span in main_span.spans if "assistant_response" in span.name
    ]
    assert (
        len(assistant_spans) == 1
    ), f"Expected 1 assistant response span, got {len(assistant_spans)}"

    assistant_span = assistant_spans[0]
    assert assistant_span.name == "assistant_response_1"
    assert "text_content" in assistant_span.output
    assert assistant_span.output["text_content"] == [
        "This is a mocked assistant response for creating files."
    ]

    # Find result span
    result_spans = [span for span in main_span.spans if "result" in span.name]
    assert len(result_spans) == 1, f"Expected 1 result span, got {len(result_spans)}"

    result_span = result_spans[0]
    assert result_span.name == "conversation_result"
    assert "message_data" in result_span.output


def test_track_claude_code__with_options__metadata_extracted(fake_backend):
    """Test tracking with ClaudeCodeOptions and metadata extraction."""
    tracked_query = track_claude_code(mock_query_with_tools)

    async def run_query():
        options = MockClaudeCodeOptions(
            system_prompt="You are a helpful coding assistant.",
            allowed_tools=["Read", "Write"],
            max_turns=3,
        )

        async for message in tracked_query(
            "Create a file called hello.txt with 'Hello, World!' in it", options=options
        ):
            pass  # Just consume the messages

    # Execute the async function
    asyncio.run(run_query())

    # Flush the tracker to ensure all data is processed
    from opik.decorator import tracker

    tracker.flush_tracker()

    # Check trace structure
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Verify we got the expected messages in the conversation flow
    conversation_flow = trace.output["conversation_flow"]
    assert len(conversation_flow) == 3  # 2 assistant messages + 1 result

    # Verify input contains args and options
    assert trace.input["args"] == [
        "Create a file called hello.txt with 'Hello, World!' in it"
    ]
    assert "kwargs" in trace.input
    assert "options" in trace.input["kwargs"]

    # The @track decorator converts the options object to string for JSON serialization
    options_str = trace.input["kwargs"]["options"]
    assert isinstance(options_str, str)
    assert "You are a helpful coding assistant." in options_str
    assert "Read" in options_str
    assert "Write" in options_str
    assert "3" in options_str

    # Verify multiple assistant responses in conversation flow
    assert trace.output["result_info"]["total_messages_count"] == 3
    assert trace.output["result_info"]["total_cost_usd"] == 0.002

    # Should have 1 main span with child spans for assistant messages
    assert len(trace.spans) == 1, f"Expected 1 main span, got {len(trace.spans)}"
    main_span = trace.spans[0]

    # Should have multiple child spans for assistant messages (2 assistant + 1 result = 3 total)
    assistant_spans = [
        span for span in main_span.spans if "assistant_response" in span.name
    ]
    assert (
        len(assistant_spans) == 2
    ), f"Expected 2 assistant response spans, got {len(assistant_spans)}"

    # Verify individual assistant spans
    assert assistant_spans[0].name == "assistant_response_1"
    assert assistant_spans[0].output["text_content"] == [
        "I'll help you create that file."
    ]

    assert assistant_spans[1].name == "assistant_response_2"
    assert assistant_spans[1].output["text_content"] == [
        "File created successfully with the content 'Hello, World!'."
    ]

    # Final response should be in the conversation flow
    conversation_flow = trace.output["conversation_flow"]
    assert len(conversation_flow) == 3  # 2 assistant + 1 result


def test_track_claude_code_with_options__custom_project(fake_backend):
    """Test advanced tracking with custom project name."""
    custom_project = "custom_claude_project"
    tracked_query = track_claude_code_with_options(
        mock_query_basic, project_name=custom_project
    )

    async def run_query():
        async for message in tracked_query("Simple test query"):
            pass  # Just consume the messages

    # Execute the async function
    asyncio.run(run_query())

    # Flush the tracker to ensure all data is processed
    from opik.decorator import tracker

    tracker.flush_tracker()

    # Check that trace was created
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Verify basic structure
    assert trace.name == "claude_code_query"
    assert trace.input == {"args": ["Simple test query"], "kwargs": {}}

    # Note: Project name verification depends on how the fake_backend handles projects
    # In real usage, this would be sent to the specified project


def test_track_claude_code__error_handling(fake_backend):
    """Test error handling in tracked Claude Code queries."""

    async def mock_query_error(prompt: str, options=None) -> AsyncGenerator[Any, None]:
        """Mock query function that raises an error."""
        yield MockAssistantMessage([MockTextBlock("Starting to process...")])
        raise ValueError("Simulated API error")

    tracked_query = track_claude_code(mock_query_error)

    async def run_query():
        try:
            async for message in tracked_query("This will fail"):
                pass  # Just consume the messages
        except ValueError:
            pass  # Expected error

    # Execute the async function
    asyncio.run(run_query())

    # Flush the tracker to ensure all data is processed
    from opik.decorator import tracker

    tracker.flush_tracker()

    # Should still create a trace even with error
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Error should be captured - check both output and error_info
    has_error_in_output = trace.output is not None and "error" in trace.output
    has_error_info = trace.error_info is not None

    # At least one should contain error information
    assert (
        has_error_in_output or has_error_info
    ), f"No error information found. Output: {trace.output}, Error info: {trace.error_info}"

    if has_error_in_output:
        assert "Simulated API error" in trace.output["error"]
    if has_error_info:
        assert "Simulated API error" in str(trace.error_info)


def test_track_claude_code__no_messages(fake_backend):
    """Test tracking when no messages are yielded."""

    async def mock_query_empty(prompt: str, options=None) -> AsyncGenerator[Any, None]:
        """Mock query function that yields no messages."""
        # Yield nothing
        if False:
            yield

    tracked_query = track_claude_code(mock_query_empty)

    async def run_query():
        async for message in tracked_query("Empty query"):
            pass  # Just consume the messages

    # Execute the async function
    asyncio.run(run_query())

    # Flush the tracker to ensure all data is processed
    from opik.decorator import tracker

    tracker.flush_tracker()

    # Should still create a trace
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Should have empty conversation flow
    assert trace.output["result_info"]["total_messages_count"] == 0
    assert trace.output["result_info"]["total_cost_usd"] == 0.0

    # Should have empty conversation flow since no messages
    assert len(trace.output["conversation_flow"]) == 0

    # Should have 1 main span but no child spans since no assistant messages were yielded
    assert len(trace.spans) == 1, f"Expected 1 main span, got {len(trace.spans)}"
    main_span = trace.spans[0]

    # Main span should have no child spans since no messages were yielded at all
    assert (
        len(main_span.spans) == 0
    ), f"Expected 0 child spans (no messages), got {len(main_span.spans)}"
