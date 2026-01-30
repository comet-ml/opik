"""
Unit tests for the code_agent_trace converters module.

Tests the conversion of Agent Trace records to Opik trace format with OpenAI messages.
"""

import datetime
from typing import Any, Dict, List

import pytest

from opik.integrations.code_agent_trace import converters


class TestCalculateEndTime:
    """Tests for _calculate_end_time function."""

    def test_calculate_end_time__with_duration__returns_correct_end_time(self):
        """Test end time calculation with duration."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)
        duration_ms = 5000.0  # 5 seconds

        result = converters._calculate_end_time(start_time, duration_ms)

        expected = datetime.datetime(2024, 1, 30, 8, 0, 5)
        assert result == expected

    def test_calculate_end_time__no_duration__returns_start_time(self):
        """Test end time calculation without duration."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)

        result = converters._calculate_end_time(start_time, None)

        assert result == start_time

    def test_calculate_end_time__fractional_duration__returns_correct_end_time(self):
        """Test end time calculation with fractional milliseconds."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)
        duration_ms = 1500.5  # 1.5005 seconds

        result = converters._calculate_end_time(start_time, duration_ms)

        expected = datetime.datetime(2024, 1, 30, 8, 0, 1, 500500)
        assert result == expected


class TestShortenPath:
    """Tests for _shorten_path function."""

    def test_shorten_path__short_path__returns_unchanged(self):
        """Test that short paths are returned unchanged."""
        path = "src/file.py"
        result = converters._shorten_path(path, max_length=40)
        assert result == path

    def test_shorten_path__long_path__returns_shortened(self):
        """Test that long paths are shortened."""
        path = "very/long/path/to/some/deeply/nested/file.py"
        result = converters._shorten_path(path, max_length=20)
        assert "..." in result
        assert result.endswith("file.py")


class TestShortenCommand:
    """Tests for _shorten_command function."""

    def test_shorten_command__short_command__returns_unchanged(self):
        """Test that short commands are returned unchanged."""
        command = "git status"
        result = converters._shorten_command(command, max_length=60)
        assert result == command

    def test_shorten_command__with_cd_prefix__removes_prefix(self):
        """Test that cd && prefix is removed."""
        command = "cd /some/path && git status"
        result = converters._shorten_command(command, max_length=60)
        assert result == "git status"

    def test_shorten_command__long_command__truncates(self):
        """Test that long commands are truncated."""
        command = "a" * 100
        result = converters._shorten_command(command, max_length=20)
        assert len(result) == 20
        assert result.endswith("...")


class TestCountLinesChanged:
    """Tests for _count_lines_changed function."""

    def test_count__no_file_edits__returns_zeros(self):
        """Test counting with no file edits."""
        records = [
            {"data": {"tool_type": "shell", "command": "ls"}},
        ]
        result = converters._count_lines_changed(records)
        assert result == {"lines_added": 0, "lines_deleted": 0}

    def test_count__single_edit__counts_correctly(self):
        """Test counting lines for a single edit."""
        records = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {
                            "old_string": "line1\nline2\n",
                            "new_string": "new_line1\nnew_line2\nnew_line3\n",
                        }
                    ],
                }
            },
        ]
        result = converters._count_lines_changed(records)
        assert result == {"lines_added": 3, "lines_deleted": 2}

    def test_count__multiple_edits__sums_correctly(self):
        """Test counting lines across multiple edits."""
        records = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "old1\n", "new_string": "new1\nnew2\n"},
                    ],
                }
            },
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "old2\nold3\n", "new_string": "new3\n"},
                    ],
                }
            },
        ]
        result = converters._count_lines_changed(records)
        assert result == {"lines_added": 3, "lines_deleted": 3}

    def test_count__empty_strings__counts_as_zero(self):
        """Test that empty strings count as zero lines."""
        records = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "", "new_string": "new_line\n"},
                    ],
                }
            },
        ]
        result = converters._count_lines_changed(records)
        assert result == {"lines_added": 1, "lines_deleted": 0}

    def test_count__whitespace_only_lines__not_counted(self):
        """Test that whitespace-only lines are not counted."""
        records = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "  \n\n\t\n", "new_string": "actual_content\n"},
                    ],
                }
            },
        ]
        result = converters._count_lines_changed(records)
        assert result == {"lines_added": 1, "lines_deleted": 0}


class TestGroupRecordsByGeneration:
    """Tests for group_records_by_generation function."""

    def test_group__multiple_generations__groups_correctly(self):
        """Test grouping records by generation_id."""
        records: List[Dict[str, Any]] = [
            {"id": "1", "generation_id": "gen-1"},
            {"id": "2", "generation_id": "gen-2"},
            {"id": "3", "generation_id": "gen-1"},
            {"id": "4", "generation_id": "gen-2"},
        ]

        result = converters.group_records_by_generation(records)

        assert len(result) == 2
        assert len(result["gen-1"]) == 2
        assert len(result["gen-2"]) == 2
        assert result["gen-1"][0]["id"] == "1"
        assert result["gen-1"][1]["id"] == "3"

    def test_group__no_generation_id__creates_unique_groups(self):
        """Test that records without generation_id get unique groups."""
        records: List[Dict[str, Any]] = [
            {"id": "1"},
            {"id": "2"},
            {"id": "3"},
        ]

        result = converters.group_records_by_generation(records)

        # Each record without generation_id should be in its own group
        assert len(result) == 3

    def test_group__mixed__handles_both(self):
        """Test grouping with mixed records (with and without generation_id)."""
        records: List[Dict[str, Any]] = [
            {"id": "1", "generation_id": "gen-1"},
            {"id": "2"},
            {"id": "3", "generation_id": "gen-1"},
        ]

        result = converters.group_records_by_generation(records)

        # One group for gen-1, one unique group for record without generation_id
        assert len(result) == 2
        assert len(result["gen-1"]) == 2


class TestBuildSpanForTool:
    """Tests for _build_span_for_tool function."""

    def test_build__shell_command__correct_structure(self):
        """Test shell command span has correct input/output structure."""
        record: Dict[str, Any] = {
            "id": "record-1",
            "timestamp": "2024-01-30T08:00:00Z",
            "event": "tool_execution",
            "data": {
                "tool_type": "shell",
                "command": "git status",
                "output": "On branch main",
                "duration_ms": 150.5,
            },
        }

        result = converters._build_span_for_tool(
            record=record,
            trace_id="parent-trace-id",
            project_name="test-project",
        )

        assert result["type"] == "tool"
        assert result["name"] == "Shell: git status"
        assert result["input"] == {"command": "git status"}
        assert result["output"]["output"] == "On branch main"
        assert result["output"]["duration_ms"] == 150.5
        assert result["trace_id"] == "parent-trace-id"

    def test_build__file_edit__correct_structure(self):
        """Test file edit span has correct input/output structure."""
        record: Dict[str, Any] = {
            "id": "record-1",
            "timestamp": "2024-01-30T08:00:00Z",
            "event": "tool_execution",
            "data": {
                "tool_type": "file_edit",
                "file_path": "src/main.py",
                "edits": [{"old_string": "foo", "new_string": "bar"}],
                "line_ranges": [{"start_line": 10, "end_line": 25}],
            },
        }

        result = converters._build_span_for_tool(
            record=record,
            trace_id="parent-trace-id",
            project_name="test-project",
        )

        assert result["type"] == "general"
        assert result["name"] == "Edit: src/main.py"
        assert result["input"]["file_path"] == "src/main.py"
        assert result["output"]["line_ranges"] == [{"start_line": 10, "end_line": 25}]


class TestConvertGenerationToTraceAndSpans:
    """Tests for convert_generation_to_trace_and_spans function."""

    def test_convert__single_tool_execution__creates_trace_with_one_span(self):
        """Test conversion of a single tool execution."""
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
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
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        # Input should contain all conversation messages (user + assistant with tool_calls + tool results)
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
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
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
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
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
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        assert len(result["spans"]) == 3
        assert result["trace"]["name"] == "Agent Turn: 2 edits, 1 command"

    def test_convert__sorts_by_timestamp__chronological_order(self):
        """Test that operations are sorted by timestamp."""
        records: List[Dict[str, Any]] = [
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

        result = converters.convert_generation_to_trace_and_spans(
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
            converters.convert_generation_to_trace_and_spans(
                generation_id="gen-1",
                records=[],
                project_name="test-project",
            )

    def test_convert__includes_model_in_metadata(self):
        """Test that model is included in trace metadata."""
        records: List[Dict[str, Any]] = [
            {
                "id": "record-1",
                "timestamp": "2024-01-30T08:00:00Z",
                "event": "tool_execution",
                "generation_id": "gen-1",
                "model": "claude-3-opus",
                "data": {"tool_type": "file_edit", "file_path": "test.py"},
            }
        ]

        result = converters.convert_generation_to_trace_and_spans(
            generation_id="gen-1",
            records=records,
            project_name="test-project",
        )

        metadata = result["trace"]["metadata"]
        assert metadata["model"] == "claude-3-opus"
        assert metadata["source"] == "cursor-agent-trace"


class TestParseTimestamp:
    """Tests for _parse_timestamp function."""

    def test_parse_timestamp__iso_format_with_z__parses_correctly(self):
        """Test parsing ISO 8601 timestamp with Z suffix."""
        result = converters._parse_timestamp("2024-01-30T08:47:03.964Z")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 30
        assert result.hour == 8
        assert result.minute == 47

    def test_parse_timestamp__iso_format_with_offset__parses_correctly(self):
        """Test parsing ISO 8601 timestamp with timezone offset."""
        result = converters._parse_timestamp("2024-01-30T08:47:03+00:00")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 30

    def test_parse_timestamp__none__returns_current_time(self):
        """Test that None timestamp returns current time."""
        result = converters._parse_timestamp(None)
        assert isinstance(result, datetime.datetime)
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        diff = abs(
            (now_utc - result.replace(tzinfo=datetime.timezone.utc)).total_seconds()
        )
        assert diff < 60  # Within 1 minute

    def test_parse_timestamp__invalid_format__returns_current_time(self):
        """Test that invalid timestamp returns current time."""
        result = converters._parse_timestamp("not-a-timestamp")
        assert isinstance(result, datetime.datetime)
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        diff = abs(
            (now_utc - result.replace(tzinfo=datetime.timezone.utc)).total_seconds()
        )
        assert diff < 60  # Within 1 minute


class TestLegacyGroupTracesByGeneration:
    """Tests for legacy group_traces_by_generation function."""

    def test_group__old_format__groups_correctly(self):
        """Test grouping traces with old format (metadata.generation_id)."""
        traces: List[Dict[str, Any]] = [
            {"id": "1", "metadata": {"generation_id": "gen-1"}},
            {"id": "2", "metadata": {"generation_id": "gen-2"}},
            {"id": "3", "metadata": {"generation_id": "gen-1"}},
        ]

        result = converters.group_traces_by_generation(traces)

        assert len(result) == 2
        assert len(result["gen-1"]) == 2
        assert len(result["gen-2"]) == 1

    def test_group__new_format__groups_correctly(self):
        """Test grouping traces with new format (top-level generation_id)."""
        traces: List[Dict[str, Any]] = [
            {"id": "1", "generation_id": "gen-1"},
            {"id": "2", "generation_id": "gen-2"},
            {"id": "3", "generation_id": "gen-1"},
        ]

        result = converters.group_traces_by_generation(traces)

        assert len(result) == 2
        assert len(result["gen-1"]) == 2
        assert len(result["gen-2"]) == 1
