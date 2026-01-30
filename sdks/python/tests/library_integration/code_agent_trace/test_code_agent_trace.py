"""
Integration tests for code_agent_trace using fake_backend.

Tests the full flow of logging agent traces to Opik.
"""

import json
from pathlib import Path

import opik
from opik.integrations.code_agent_trace import log_code_agent_turn

from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    FeedbackScoreModel,
    SpanModel,
    TraceModel,
    assert_equal,
)


TEST_TRACES_FILE = Path(__file__).parent / "test_traces.jsonl"


def _load_test_records():
    """Load test records from the test traces file."""
    records = []
    with open(TEST_TRACES_FILE, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                records.append(json.loads(line))
    return records


def test_log_code_agent_turn__full_flow__creates_trace_and_spans(fake_backend):
    """
    Test that log_code_agent_turn creates a trace with correct structure.

    This test uses a real traces.jsonl file and verifies:
    - Trace is created with correct metadata
    - Input is in OpenAI messages format
    - Output is in OpenAI chat completion format
    - Spans are created for tool executions
    - Feedback scores are included
    """
    records = _load_test_records()

    # Log the agent turn
    log_code_agent_turn(records, project_name="test-code-agent-trace")

    # Flush to ensure all data is sent
    opik.flush_tracker()

    # Verify trace was created
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Define expected trace structure using tree-based assertion
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="Agent Turn: 1 edit, 1 command",
        input={
            "messages": [
                # User message
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
                    "tool_calls": ANY_LIST,
                },
                # Tool result for file_edit
                {
                    "role": "tool",
                    "tool_call_id": "call_record-2",
                    "name": "file_edit",
                    "content": ANY_STRING,
                },
                # Tool result for shell
                {
                    "role": "tool",
                    "tool_call_id": "call_record-3",
                    "name": "shell",
                    "content": ANY_STRING,
                },
            ]
        },
        output={
            "id": "chatcmpl-gen-test-456",
            "object": "chat.completion",
            "created": ANY_BUT_NONE,
            "model": "claude-4-opus",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": ANY_STRING.containing("I've added a hello world function"),
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
                value=ANY_BUT_NONE,
            ),
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="lines_added",
                value=2,  # 2 non-empty lines added
            ),
            FeedbackScoreModel(
                id=ANY_BUT_NONE,
                name="lines_deleted",
                value=0,
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


def test_log_code_agent_turn__thread_id__set_from_conversation_id(fake_backend):
    """Test that thread_id is set from conversation_id."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-thread-id")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Use tree-based assertion for thread_id
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        thread_id="conv-test-123",
        name=ANY_STRING,
        input=ANY_DICT,
        output=ANY_DICT,
        metadata=ANY_DICT.containing({"conversation_id": "conv-test-123"}),
        tags=ANY_LIST,
        feedback_scores=ANY_LIST,
        spans=ANY_LIST,
    )

    assert_equal(EXPECTED_TRACE, trace_tree)


def test_log_code_agent_turn__output_format__is_chat_completion(fake_backend):
    """Test that output is in OpenAI chat completion format."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-output-format")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Use tree-based assertion for output format
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name=ANY_STRING,
        input=ANY_DICT,
        output={
            "id": ANY_STRING.starting_with("chatcmpl-"),
            "object": "chat.completion",
            "created": ANY_BUT_NONE,
            "model": "claude-4-opus",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": ANY_STRING,
                        "refusal": None,
                    },
                    "finish_reason": "stop",
                }
            ],
        },
        metadata=ANY_DICT,
        tags=ANY_LIST,
        thread_id=ANY_BUT_NONE,
        feedback_scores=ANY_LIST,
        spans=ANY_LIST,
    )

    assert_equal(EXPECTED_TRACE, trace_tree)


def test_log_code_agent_turn__input_format__is_messages_array(fake_backend):
    """Test that input is in OpenAI messages array format."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-input-format")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Use tree-based assertion for input format
    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name=ANY_STRING,
        output=ANY_DICT,
        input={
            "messages": [
                {"role": "user", "content": ANY_LIST},
                {"role": "assistant", "content": ANY, "tool_calls": ANY_LIST, "refusal": ANY, "audio": ANY, "function_call": ANY},
                {"role": "tool", "tool_call_id": ANY_STRING, "name": "file_edit", "content": ANY_STRING},
                {"role": "tool", "tool_call_id": ANY_STRING, "name": "shell", "content": ANY_STRING},
            ]
        },
        metadata=ANY_DICT,
        tags=ANY_LIST,
        thread_id=ANY_BUT_NONE,
        feedback_scores=ANY_LIST,
        spans=ANY_LIST,
    )

    assert_equal(EXPECTED_TRACE, trace_tree)
