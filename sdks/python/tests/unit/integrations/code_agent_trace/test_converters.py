"""
Unit tests for the code_agent_trace converters module.

Tests the public conversion functions only.
"""

from typing import List

import pytest

from opik.integrations.code_agent_trace import (
    convert_generation_to_trace_and_spans,
    group_records_by_generation,
)
from opik.integrations.code_agent_trace.types import TraceRecord


class TestGroupRecordsByGeneration:
    """Tests for group_records_by_generation function."""

    def test_group__multiple_generations__groups_correctly(self):
        """Test grouping records by generation_id."""
        records: List[TraceRecord] = [
            {"id": "1", "generation_id": "gen-1"},
            {"id": "2", "generation_id": "gen-2"},
            {"id": "3", "generation_id": "gen-1"},
            {"id": "4", "generation_id": "gen-2"},
        ]

        result = group_records_by_generation(records)

        assert len(result) == 2
        assert len(result["gen-1"]) == 2
        assert len(result["gen-2"]) == 2
        assert result["gen-1"][0]["id"] == "1"
        assert result["gen-1"][1]["id"] == "3"

    def test_group__no_generation_id__creates_unique_groups(self):
        """Test that records without generation_id get unique groups."""
        records: List[TraceRecord] = [
            {"id": "1"},
            {"id": "2"},
            {"id": "3"},
        ]

        result = group_records_by_generation(records)

        # Each record without generation_id should be in its own group
        assert len(result) == 3

    def test_group__mixed__handles_both(self):
        """Test grouping with mixed records (with and without generation_id)."""
        records: List[TraceRecord] = [
            {"id": "1", "generation_id": "gen-1"},
            {"id": "2"},
            {"id": "3", "generation_id": "gen-1"},
        ]

        result = group_records_by_generation(records)

        # One group for gen-1, one unique group for record without generation_id
        assert len(result) == 2
        assert len(result["gen-1"]) == 2


class TestConvertGenerationToTraceAndSpans:
    """Tests for convert_generation_to_trace_and_spans function."""

    def test_convert__single_tool_execution__creates_trace_with_one_span(self):
        """Test conversion of a single tool execution."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "conversation_id": "conv-1",
                "data": {
                    "tool_type": "file_edit",
                    "file_path": "test.py",
                },
            }
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        assert "trace" in result
        assert "spans" in result
        assert len(result["spans"]) == 1
        assert result["trace"]["thread_id"] == "conv-1"
        assert result["trace"]["name"] == "Agent Turn: 1 edit"

    def test_convert__with_user_message__includes_in_input(self):
        """Test that user message and tool interactions are in input."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "user_message",
                "generation_id": "gen-1",
                "data": {"content": "Please fix the bug"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "test.py"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should contain all conversation messages
        input_messages = result["trace"]["input"]["messages"]
        assert len(input_messages) == 3  # user, assistant with tool_calls, tool result

        # First message is user
        assert input_messages[0]["role"] == "user"
        content = input_messages[0]["content"]
        assert len(content) == 1
        assert content[0]["type"] == "text"
        assert content[0]["text"] == "Please fix the bug"

        # Second message is assistant with tool_calls
        assert input_messages[1]["role"] == "assistant"
        assert "tool_calls" in input_messages[1]

        # Third message is tool result
        assert input_messages[2]["role"] == "tool"

    def test_convert__with_assistant_response__includes_in_output(self):
        """Test that assistant response is in output as chat completion format."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "user_message",
                "generation_id": "gen-1",
                "model": "gpt-4",
                "data": {"content": "Hello"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "assistant_message",
                "generation_id": "gen-1",
                "model": "gpt-4",
                "data": {"content": "I'll help you with that."},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should have user message
        input_messages = result["trace"]["input"]["messages"]
        assert len(input_messages) == 1
        assert input_messages[0]["role"] == "user"

        # Output should be OpenAI chat completion format
        output = result["trace"]["output"]
        assert output["object"] == "chat.completion"
        assert "choices" in output
        assert len(output["choices"]) == 1
        assert output["choices"][0]["message"]["role"] == "assistant"
        assert output["choices"][0]["message"]["content"] == "I'll help you with that."
        assert output["choices"][0]["finish_reason"] == "stop"

    def test_convert__with_tool_calls__includes_tool_messages_in_input(self):
        """Test that tool executions are in input, output has tool_calls."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "model": "gpt-4",
                "data": {
                    "tool_type": "shell",
                    "command": "npm install",
                    "output": "Done",
                    "duration_ms": 3000,
                },
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should have assistant with tool_calls and tool result
        input_messages = result["trace"]["input"]["messages"]
        assert any(m.get("role") == "assistant" for m in input_messages)
        assert any(m.get("role") == "tool" for m in input_messages)

        # Output should be chat completion format with tool_calls
        output = result["trace"]["output"]
        assert output["object"] == "chat.completion"
        assert output["choices"][0]["message"]["tool_calls"] is not None
        assert output["choices"][0]["finish_reason"] == "tool_calls"

    def test_convert__multiple_operations__correct_name(self):
        """Test conversion of multiple operations has correct name."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "file1.py"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "shell", "command": "npm install"},
            },
            {
                "id": "record-3",
                "timestamp": "2024-01-30T08:00:10Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "file2.py"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        assert len(result["spans"]) == 3
        assert result["trace"]["name"] == "Agent Turn: 2 edits, 1 command"

    def test_convert__sorts_by_timestamp__chronological_order(self):
        """Test that operations are sorted by timestamp."""
        records: List[TraceRecord] = [
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:10Z",  # Later
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "second.py"},
            },
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",  # Earlier
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "first.py"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Spans should be in chronological order
        assert result["spans"][0]["name"] == "Edit: first.py"
        assert result["spans"][1]["name"] == "Edit: second.py"

    def test_convert__empty_records__raises_error(self):
        """Test that empty record list raises an error."""
        with pytest.raises(ValueError, match="Cannot convert empty record list"):
            convert_generation_to_trace_and_spans(
                generation_id="gen-1",
                records=[],
                project_name="test-project",
            )

    def test_convert__includes_model_in_metadata(self):
        """Test that model is included in trace metadata."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "model": "claude-3-opus",
                "data": {"tool_type": "file_edit", "file_path": "test.py"},
            }
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        metadata = result["trace"]["metadata"]
        assert metadata["model"] == "claude-3-opus"
        assert metadata["source"] == "cursor-agent-trace"

    def test_convert__includes_feedback_scores(self):
        """Test that feedback scores are included in trace."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {
                    "tool_type": "file_edit",
                    "file_path": "test.py",
                    "edits": [
                        {"old_string": "old\ncode\n", "new_string": "new\ncode\nhere\n"}
                    ],
                },
            }
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        feedback_scores = result["trace"]["feedback_scores"]
        assert len(feedback_scores) == 3

        score_names = {s["name"] for s in feedback_scores}
        assert "duration_seconds" in score_names
        assert "lines_added" in score_names
        assert "lines_deleted" in score_names

        # Check lines changed values
        lines_added = next(s for s in feedback_scores if s["name"] == "lines_added")
        lines_deleted = next(s for s in feedback_scores if s["name"] == "lines_deleted")
        assert lines_added["value"] == 3  # 3 non-empty lines
        assert lines_deleted["value"] == 2  # 2 non-empty lines

    def test_convert__includes_user_email_in_metadata(self):
        """Test that user_email is included in trace metadata."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "user_email": "developer@example.com",
                "data": {"tool_type": "file_edit", "file_path": "test.py"},
            }
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        metadata = result["trace"]["metadata"]
        assert metadata["user"] == "developer@example.com"

    def test_convert__interrupted_with_user_message__preserves_user_message_in_input(
        self,
    ):
        """Test that interrupted turns preserve the user message in input."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "user_message",
                "generation_id": "gen-1",
                "data": {"content": "Please fix the bug"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "agent_stop",
                "generation_id": "gen-1",
                "data": {"status": "aborted"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should have the actual user message
        messages = result["trace"]["input"]["messages"]
        assert len(messages) == 1
        assert messages[0]["role"] == "user"
        assert messages[0]["content"][0]["text"] == "Please fix the bug"

        # Output should indicate interruption
        output = result["trace"]["output"]
        assert "interrupted" in output["choices"][0]["message"]["content"].lower()

    def test_convert__interrupted_with_tools__preserves_all_messages_in_input(self):
        """Test that interrupted turns with tool calls preserve all messages."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "user_message",
                "generation_id": "gen-1",
                "data": {"content": "Please fix the bug"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "data": {"tool_type": "file_edit", "file_path": "test.py"},
            },
            {
                "id": "record-3",
                "timestamp": "2024-01-30T08:00:10Z",
                "event": "agent_stop",
                "generation_id": "gen-1",
                "data": {"status": "aborted"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should have user message, assistant with tool_calls, and tool result
        messages = result["trace"]["input"]["messages"]
        assert len(messages) == 3
        assert messages[0]["role"] == "user"
        assert messages[1]["role"] == "assistant"
        assert "tool_calls" in messages[1]
        assert messages[2]["role"] == "tool"

        # Output should indicate interruption (no final response)
        output = result["trace"]["output"]
        assert "interrupted" in output["choices"][0]["message"]["content"].lower()

    def test_convert__interrupted_turn__trace_name_indicates_interrupted(self):
        """Test that interrupted turns have 'interrupted' in trace name."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "user_message",
                "generation_id": "gen-1",
                "data": {"content": "Hello"},
            },
            {
                "id": "record-2",
                "timestamp": "2024-01-30T08:00:05Z",
                "event": "agent_stop",
                "generation_id": "gen-1",
                "data": {"status": "aborted"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        assert "interrupted" in result["trace"]["name"].lower()

    def test_convert__only_assistant_response__not_interrupted(self):
        """Test that turns with assistant response are not marked as interrupted."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "assistant_message",
                "generation_id": "gen-1",
                "data": {"content": "I can help with that."},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Output should have the assistant response (not interrupted message)
        output = result["trace"]["output"]
        assert output["choices"][0]["message"]["content"] == "I can help with that."
        # Trace name should NOT have "interrupted"
        assert "interrupted" not in result["trace"]["name"].lower()

    def test_convert__no_activity_records__empty_input_messages(self):
        """Test that turns with only stop event have empty messages."""
        records: List[TraceRecord] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "agent_stop",
                "generation_id": "gen-1",
                "data": {"status": "completed"},
            },
        ]

        result = convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should have empty messages (no activity to capture)
        messages = result["trace"]["input"]["messages"]
        assert len(messages) == 0
        # Not interrupted since there was no activity
        assert "interrupted" not in result["trace"]["name"].lower()
