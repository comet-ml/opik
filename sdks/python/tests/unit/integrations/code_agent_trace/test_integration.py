"""
Unit tests for code_agent_trace using fake_backend.

Tests the full flow of logging agent traces to Opik.
"""

import json
from pathlib import Path

import opik
from opik.integrations.code_agent_trace import log_code_agent_turn

from tests.testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    FeedbackScoreModel,
    SpanModel,
    TraceModel,
    assert_equal,
)


TEST_DIR = Path(__file__).parent
TEST_TRACES_FILE = TEST_DIR / "test_traces.jsonl"
TEST_TRACES_INTERRUPTED_FILE = TEST_DIR / "test_traces_interrupted.jsonl"
TEST_TRACES_MULTI_GEN_FILE = TEST_DIR / "test_traces_multiple_generations.jsonl"


def _load_records_from_file(filepath: Path):
    """Load test records from a jsonl file."""
    records = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                records.append(json.loads(line))
    return records


def test_log_code_agent_turn__full_flow__creates_trace_and_spans(fake_backend):
    """
    Test that log_code_agent_turn creates a trace with correct structure.

    This test uses a real traces.jsonl file and verifies:
    - Trace is created with correct metadata
    - Input is in OpenAI messages format (user, assistant with tool_calls, tool results)
    - Output is in OpenAI chat completion format
    - Spans are created for tool executions
    - Feedback scores are included
    - thread_id is set from conversation_id
    """
    records = _load_records_from_file(TEST_TRACES_FILE)

    # Log the agent turn
    log_code_agent_turn(records, project_name="test-code-agent-trace")

    # Flush to ensure all data is sent
    opik.flush_tracker()

    # Verify trace was created
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Define expected trace structure using tree-based assertion
    # We control test_traces.jsonl, so we specify exact expected values
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="Agent Turn: 1 edit, 1 command",
        input={
            "messages": [
                # User message - exact content from test_traces.jsonl
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "Please add a hello world function to main.py",
                        }
                    ],
                },
                # Assistant message with tool_calls
                {
                    "role": "assistant",
                    "content": None,
                    "refusal": None,
                    "audio": None,
                    "function_call": None,
                    "tool_calls": [
                        {
                            "id": "call_record-2",
                            "type": "function",
                            "function": {
                                "name": "file_edit",
                                "arguments": '{"file_path": "/project/main.py", "edit_count": 1}',
                            },
                        },
                        {
                            "id": "call_record-3",
                            "type": "function",
                            "function": {
                                "name": "shell",
                                "arguments": '{"command": "python main.py", "cwd": "/project"}',
                            },
                        },
                    ],
                },
                # Tool result for file_edit
                {
                    "role": "tool",
                    "tool_call_id": "call_record-2",
                    "name": "file_edit",
                    "content": '{"file_path": "/project/main.py", "status": "success", "lines_modified": [{"start": 1, "end": 3}]}',
                },
                # Tool result for shell
                {
                    "role": "tool",
                    "tool_call_id": "call_record-3",
                    "name": "shell",
                    "content": '{"command": "python main.py", "output": "Hello, World!", "duration_ms": 150.5, "cwd": "/project"}',
                },
            ]
        },
        output={
            "id": "chatcmpl-gen-test-456",
            "object": "chat.completion",
            "created": ANY_BUT_NONE,  # Unix timestamp, varies
            "model": "claude-4-opus",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        # Content includes final response + code changes summary
                        "content": ANY_BUT_NONE,
                        "refusal": None,
                    },
                    "finish_reason": "stop",
                }
            ],
        },
        metadata={
            "source": "cursor-agent-trace",
            "model": "claude-4-opus",
            "generation_id": "gen-test-456",
            "conversation_id": "conv-test-123",
            "user": "test@example.com",
        },
        tags=["agent-trace"],
        thread_id="conv-test-123",
        project_name="test-code-agent-trace",
        feedback_scores=[
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="duration_seconds",
                value=3.0,  # 3 seconds from 08:00:00 to 08:00:03
            ),
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="lines_added",
                value=2,  # 2 non-empty lines: "def hello():" and "print(...)"
            ),
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="lines_deleted",
                value=0,  # old_string was empty
            ),
        ],
        spans=[
            # File edit span
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                name="Edit: /project/main.py",
                type="general",
                input={
                    "file_path": "/project/main.py",
                    "edits": [
                        {
                            "old_string": "",
                            "new_string": 'def hello():\n    print("Hello, World!")\n',
                        }
                    ],
                },
                output={"line_ranges": [{"start_line": 1, "end_line": 3}]},
                metadata={"record_id": "record-2", "tool_type": "file_edit"},
                tags=["agent-trace"],
                project_name="test-code-agent-trace",
            ),
            # Shell command span
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                name="Shell: python main.py",
                type="tool",
                input={"command": "python main.py", "cwd": "/project"},
                output={"output": "Hello, World!", "duration_ms": 150.5},
                metadata={"record_id": "record-3", "tool_type": "shell"},
                tags=["agent-trace"],
                project_name="test-code-agent-trace",
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE, trace_tree)

    # Additional assertions for output content (contains both response and code changes)
    output_content = trace_tree.output["choices"][0]["message"]["content"]
    assert "I've added a hello world function" in output_content
    assert "## Code Changes Summary" in output_content
    assert "`main.py`" in output_content


def test_log_code_agent_turn__interrupted_turn__preserves_captured_data(fake_backend):
    """
    Test that interrupted turns preserve all captured data in input.

    Uses test_traces_interrupted.jsonl which has:
    - User message
    - One file edit tool execution
    - Agent stop event (interrupted)
    - NO final assistant response

    Verifies:
    - Input contains user message and tool interactions
    - Output indicates interruption
    - Trace name includes "interrupted"
    """
    records = _load_records_from_file(TEST_TRACES_INTERRUPTED_FILE)

    log_code_agent_turn(records, project_name="test-interrupted")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Verify trace structure for interrupted turn
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="Agent Turn: interrupted, 1 edit",
        input={
            "messages": [
                # User message preserved
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Please refactor the database module"}
                    ],
                },
                # Assistant with tool_calls preserved
                {
                    "role": "assistant",
                    "content": None,
                    "refusal": None,
                    "audio": None,
                    "function_call": None,
                    "tool_calls": [
                        {
                            "id": "call_record-2",
                            "type": "function",
                            "function": {
                                "name": "file_edit",
                                "arguments": '{"file_path": "/project/db.py", "edit_count": 1}',
                            },
                        }
                    ],
                },
                # Tool result preserved
                {
                    "role": "tool",
                    "tool_call_id": "call_record-2",
                    "name": "file_edit",
                    "content": ANY_BUT_NONE,  # JSON string with file details
                },
            ]
        },
        output=ANY_DICT.containing(
            {
                "object": "chat.completion",
                "choices": [
                    {
                        "index": 0,
                        "message": ANY_DICT.containing(
                            {
                                "role": "assistant",
                                # Content starts with interrupted message
                                "content": ANY_BUT_NONE,
                            }
                        ),
                        "finish_reason": "stop",
                    }
                ],
            }
        ),
        metadata=ANY_DICT.containing(
            {
                "source": "cursor-agent-trace",
                "user": "test@example.com",
            }
        ),
        tags=["agent-trace"],
        thread_id="conv-interrupted",
        project_name="test-interrupted",
        feedback_scores=ANY_LIST,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                name="Edit: /project/db.py",
                type="general",
                input=ANY_DICT.containing({"file_path": "/project/db.py"}),
                output=ANY_DICT,
                metadata={"record_id": "record-2", "tool_type": "file_edit"},
                tags=["agent-trace"],
                project_name="test-interrupted",
            )
        ],
    )

    assert_equal(EXPECTED_TRACE, trace_tree)

    # Verify output contains interrupted message
    output_content = trace_tree.output["choices"][0]["message"]["content"]
    assert "[Agent turn was interrupted]" in output_content


def test_log_code_agent_turn__multiple_generations__creates_separate_traces(
    fake_backend,
):
    """
    Test that multiple generation_ids create separate traces when grouped.

    Uses test_traces_multiple_generations.jsonl which has:
    - Two complete agent turns with different generation_ids
    - Same conversation_id (thread)

    Verifies:
    - Two separate traces are created (one per generation_id)
    - Each trace has correct content
    - Both share the same thread_id
    """
    from collections import defaultdict

    records = _load_records_from_file(TEST_TRACES_MULTI_GEN_FILE)

    # Group records by generation_id (as shown in API docstring)
    turns: dict = defaultdict(list)
    for record in records:
        gen_id = record.get("generation_id", "unknown")
        turns[gen_id].append(record)

    # Log each turn separately
    for generation_id, turn_records in turns.items():
        log_code_agent_turn(turn_records, project_name="test-multi-gen")

    opik.flush_tracker()

    # Should create 2 traces (one per generation_id)
    assert len(fake_backend.trace_trees) == 2

    # Sort by generation_id in metadata to get consistent order
    traces = sorted(
        fake_backend.trace_trees, key=lambda t: t.metadata.get("generation_id", "")
    )

    # Both traces should have the same thread_id
    assert traces[0].thread_id == "conv-multi"
    assert traces[1].thread_id == "conv-multi"

    # First trace (gen-first)
    first_trace = traces[0]
    assert first_trace.metadata.get("generation_id") == "gen-first"
    assert first_trace.name == "Agent Turn: 1 edit"

    # Verify first trace input has correct user message
    first_input_messages = first_trace.input["messages"]
    assert len(first_input_messages) >= 1
    assert first_input_messages[0]["role"] == "user"
    assert "logging" in first_input_messages[0]["content"][0]["text"].lower()

    # Second trace (gen-second)
    second_trace = traces[1]
    assert second_trace.metadata.get("generation_id") == "gen-second"
    assert second_trace.name == "Agent Turn: 1 edit"

    # Verify second trace input has different user message
    second_input_messages = second_trace.input["messages"]
    assert "error handling" in second_input_messages[0]["content"][0]["text"].lower()

    # Both should have spans
    assert len(first_trace.spans) == 1
    assert len(second_trace.spans) == 1
