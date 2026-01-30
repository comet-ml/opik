"""
Unit tests for the code_agent_trace helpers module.

Tests the public helper functions.
"""

import datetime
from typing import Any, Dict, List

from opik.integrations.code_agent_trace.helpers import (
    calculate_end_time,
    count_lines_changed,
    parse_timestamp,
    shorten_command,
    shorten_path,
)


class TestParseTimestamp:
    """Tests for parse_timestamp function."""

    def test_parse_timestamp__iso_format_with_z__parses_correctly(self):
        """Test parsing ISO 8601 timestamp with Z suffix."""
        result = parse_timestamp("2024-01-30T08:47:03.964Z")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 30
        assert result.hour == 8
        assert result.minute == 47

    def test_parse_timestamp__iso_format_with_offset__parses_correctly(self):
        """Test parsing ISO 8601 timestamp with timezone offset."""
        result = parse_timestamp("2024-01-30T08:47:03+00:00")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 30

    def test_parse_timestamp__none__returns_current_time(self):
        """Test that None timestamp returns current time."""
        result = parse_timestamp(None)
        assert isinstance(result, datetime.datetime)
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        diff = abs(
            (now_utc - result.replace(tzinfo=datetime.timezone.utc)).total_seconds()
        )
        assert diff < 60  # Within 1 minute

    def test_parse_timestamp__invalid_format__returns_current_time(self):
        """Test that invalid timestamp returns current time."""
        result = parse_timestamp("not-a-timestamp")
        assert isinstance(result, datetime.datetime)
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        diff = abs(
            (now_utc - result.replace(tzinfo=datetime.timezone.utc)).total_seconds()
        )
        assert diff < 60  # Within 1 minute


class TestCalculateEndTime:
    """Tests for calculate_end_time function."""

    def test_calculate_end_time__with_duration__returns_correct_end_time(self):
        """Test end time calculation with duration."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)
        duration_ms = 5000.0  # 5 seconds

        result = calculate_end_time(start_time, duration_ms)

        expected = datetime.datetime(2024, 1, 30, 8, 0, 5)
        assert result == expected

    def test_calculate_end_time__no_duration__returns_start_time(self):
        """Test end time calculation without duration."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)

        result = calculate_end_time(start_time, None)

        assert result == start_time

    def test_calculate_end_time__fractional_duration__returns_correct_end_time(self):
        """Test end time calculation with fractional milliseconds."""
        start_time = datetime.datetime(2024, 1, 30, 8, 0, 0)
        duration_ms = 1500.5  # 1.5005 seconds

        result = calculate_end_time(start_time, duration_ms)

        expected = datetime.datetime(2024, 1, 30, 8, 0, 1, 500500)
        assert result == expected


class TestShortenPath:
    """Tests for shorten_path function."""

    def test_shorten_path__short_path__returns_unchanged(self):
        """Test that short paths are returned unchanged."""
        path = "src/file.py"
        result = shorten_path(path, max_length=40)
        assert result == path

    def test_shorten_path__long_path__returns_shortened(self):
        """Test that long paths are shortened."""
        path = "very/long/path/to/some/deeply/nested/file.py"
        result = shorten_path(path, max_length=20)
        assert "..." in result
        assert result.endswith("file.py")


class TestShortenCommand:
    """Tests for shorten_command function."""

    def test_shorten_command__short_command__returns_unchanged(self):
        """Test that short commands are returned unchanged."""
        command = "git status"
        result = shorten_command(command, max_length=60)
        assert result == command

    def test_shorten_command__with_cd_prefix__removes_prefix(self):
        """Test that cd && prefix is removed."""
        command = "cd /some/path && git status"
        result = shorten_command(command, max_length=60)
        assert result == "git status"

    def test_shorten_command__long_command__truncates(self):
        """Test that long commands are truncated."""
        command = "a" * 100
        result = shorten_command(command, max_length=20)
        assert len(result) == 20
        assert result.endswith("...")


class TestCountLinesChanged:
    """Tests for count_lines_changed function."""

    def test_count__no_file_edits__returns_zeros(self):
        """Test counting with no file edits."""
        records: List[Dict[str, Any]] = [
            {"data": {"tool_type": "shell", "command": "ls"}},
        ]
        result = count_lines_changed(records)
        assert result == {"lines_added": 0, "lines_deleted": 0}

    def test_count__single_edit__counts_correctly(self):
        """Test counting lines for a single edit."""
        records: List[Dict[str, Any]] = [
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
        result = count_lines_changed(records)
        assert result == {"lines_added": 3, "lines_deleted": 2}

    def test_count__multiple_edits__sums_correctly(self):
        """Test counting lines across multiple edits."""
        records: List[Dict[str, Any]] = [
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
        result = count_lines_changed(records)
        assert result == {"lines_added": 3, "lines_deleted": 3}

    def test_count__empty_strings__counts_as_zero(self):
        """Test that empty strings count as zero lines."""
        records: List[Dict[str, Any]] = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "", "new_string": "new_line\n"},
                    ],
                }
            },
        ]
        result = count_lines_changed(records)
        assert result == {"lines_added": 1, "lines_deleted": 0}

    def test_count__whitespace_only_lines__not_counted(self):
        """Test that whitespace-only lines are not counted."""
        records: List[Dict[str, Any]] = [
            {
                "data": {
                    "tool_type": "file_edit",
                    "edits": [
                        {"old_string": "  \n\n\t\n", "new_string": "actual_content\n"},
                    ],
                }
            },
        ]
        result = count_lines_changed(records)
        assert result == {"lines_added": 1, "lines_deleted": 0}
