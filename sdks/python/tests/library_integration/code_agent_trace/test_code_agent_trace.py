"""
Integration tests for code_agent_trace using fake_backend.

Tests the full flow of logging agent traces to Opik.
"""

import json
from pathlib import Path

import opik
from opik.integrations.code_agent_trace import log_code_agent_turn

from ...testlib import (
    ANY_BUT_NONE,
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

    # Verify trace basic properties
    assert trace_tree.id is not None
    assert trace_tree.name == "Agent Turn: 1 edit, 1 command"
    assert trace_tree.project_name == "test-code-agent-trace"
    assert trace_tree.thread_id == "conv-test-123"
    
    # Verify trace metadata
    assert trace_tree.metadata["source"] == "cursor-agent-trace"
    assert trace_tree.metadata["model"] == "claude-4-opus"
    assert trace_tree.metadata["generation_id"] == "gen-test-456"
    assert trace_tree.metadata["conversation_id"] == "conv-test-123"
    assert trace_tree.metadata["user"] == "test@example.com"
    
    # Verify input is in OpenAI messages format
    assert "messages" in trace_tree.input
    messages = trace_tree.input["messages"]
    assert len(messages) == 4  # user, assistant with tool_calls, 2 tool results
    assert messages[0]["role"] == "user"
    assert messages[1]["role"] == "assistant"
    assert "tool_calls" in messages[1]
    assert messages[2]["role"] == "tool"
    assert messages[3]["role"] == "tool"
    
    # Verify output is in OpenAI chat completion format
    assert trace_tree.output["object"] == "chat.completion"
    assert trace_tree.output["model"] == "claude-4-opus"
    assert len(trace_tree.output["choices"]) == 1
    assert trace_tree.output["choices"][0]["message"]["role"] == "assistant"
    assert trace_tree.output["choices"][0]["finish_reason"] == "stop"
    
    # Verify spans were created
    assert len(trace_tree.spans) == 2
    
    # Verify file edit span
    file_edit_span = trace_tree.spans[0]
    assert file_edit_span.name == "Edit: /project/main.py"
    assert file_edit_span.type == "general"
    assert file_edit_span.input["file_path"] == "/project/main.py"
    assert file_edit_span.metadata["tool_type"] == "file_edit"
    
    # Verify shell span
    shell_span = trace_tree.spans[1]
    assert shell_span.name == "Shell: python main.py"
    assert shell_span.type == "tool"
    assert shell_span.input["command"] == "python main.py"
    assert shell_span.metadata["tool_type"] == "shell"
    
    # Verify feedback scores
    assert len(trace_tree.feedback_scores) == 3
    score_names = {s.name for s in trace_tree.feedback_scores}
    assert "duration_seconds" in score_names
    assert "lines_added" in score_names
    assert "lines_deleted" in score_names
    
    # Verify lines changed values
    lines_added = next(s for s in trace_tree.feedback_scores if s.name == "lines_added")
    lines_deleted = next(s for s in trace_tree.feedback_scores if s.name == "lines_deleted")
    assert lines_added.value == 2  # 2 non-empty lines
    assert lines_deleted.value == 0


def test_log_code_agent_turn__thread_id__set_from_conversation_id(fake_backend):
    """Test that thread_id is set from conversation_id."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-thread-id")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Thread ID should be the conversation_id
    assert trace_tree.thread_id == "conv-test-123"


def test_log_code_agent_turn__output_format__is_chat_completion(fake_backend):
    """Test that output is in OpenAI chat completion format."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-output-format")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Verify output structure
    output = trace_tree.output
    assert output["object"] == "chat.completion"
    assert "choices" in output
    assert len(output["choices"]) == 1
    assert output["choices"][0]["message"]["role"] == "assistant"
    assert output["choices"][0]["finish_reason"] == "stop"


def test_log_code_agent_turn__input_format__is_messages_array(fake_backend):
    """Test that input is in OpenAI messages array format."""
    records = _load_test_records()

    log_code_agent_turn(records, project_name="test-input-format")
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Verify input structure
    input_data = trace_tree.input
    assert "messages" in input_data
    messages = input_data["messages"]

    # Should have: user, assistant with tool_calls, tool result, tool result
    assert len(messages) == 4
    assert messages[0]["role"] == "user"
    assert messages[1]["role"] == "assistant"
    assert messages[2]["role"] == "tool"
    assert messages[3]["role"] == "tool"
