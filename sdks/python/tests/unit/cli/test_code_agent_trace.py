"""
Unit tests for the code_agent_trace CLI command.

Tests the CLI functions for uploading Agent Trace data to Opik.
"""

import json
import tempfile
from pathlib import Path
from typing import Any, Dict, List


from opik.cli import code_agent_trace


class TestReadTracesFile:
    """Tests for _read_traces_file function."""

    def test_read__valid_jsonl__returns_records(self):
        """Test reading a valid JSONL file."""
        records = [
            {"id": "1", "event": "user_message"},
            {"id": "2", "event": "tool_execution"},
        ]

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            for record in records:
                f.write(json.dumps(record) + "\n")
            f.flush()

            result = code_agent_trace._read_traces_file(Path(f.name))

        assert len(result) == 2
        assert result[0]["id"] == "1"
        assert result[1]["id"] == "2"

    def test_read__empty_lines__skips_them(self):
        """Test that empty lines are skipped."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            f.write('{"id": "1"}\n')
            f.write("\n")
            f.write('{"id": "2"}\n')
            f.write("   \n")
            f.flush()

            result = code_agent_trace._read_traces_file(Path(f.name))

        assert len(result) == 2

    def test_read__invalid_json__skips_line(self, capsys):
        """Test that invalid JSON lines are skipped with warning."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            f.write('{"id": "1"}\n')
            f.write("not valid json\n")
            f.write('{"id": "2"}\n')
            f.flush()

            result = code_agent_trace._read_traces_file(Path(f.name))

        assert len(result) == 2


class TestGetConversationId:
    """Tests for _get_conversation_id function."""

    def test_get__new_format__returns_id(self):
        """Test getting conversation_id from new format."""
        record = {"conversation_id": "conv-123"}
        result = code_agent_trace._get_conversation_id(record)
        assert result == "conv-123"

    def test_get__old_format__returns_id(self):
        """Test getting conversation_id from old format (metadata)."""
        record = {"metadata": {"conversation_id": "conv-456"}}
        result = code_agent_trace._get_conversation_id(record)
        assert result == "conv-456"

    def test_get__missing__returns_none(self):
        """Test that missing conversation_id returns None."""
        record = {"id": "1"}
        result = code_agent_trace._get_conversation_id(record)
        assert result is None

    def test_get__new_format_takes_precedence(self):
        """Test that new format takes precedence over old format."""
        record = {
            "conversation_id": "new-conv",
            "metadata": {"conversation_id": "old-conv"},
        }
        result = code_agent_trace._get_conversation_id(record)
        assert result == "new-conv"


class TestGetGenerationId:
    """Tests for _get_generation_id function."""

    def test_get__new_format__returns_id(self):
        """Test getting generation_id from new format."""
        record = {"generation_id": "gen-123"}
        result = code_agent_trace._get_generation_id(record)
        assert result == "gen-123"

    def test_get__old_format__returns_id(self):
        """Test getting generation_id from old format (metadata)."""
        record = {"metadata": {"generation_id": "gen-456"}}
        result = code_agent_trace._get_generation_id(record)
        assert result == "gen-456"

    def test_get__missing__returns_none(self):
        """Test that missing generation_id returns None."""
        record = {"id": "1"}
        result = code_agent_trace._get_generation_id(record)
        assert result is None


class TestGroupByGeneration:
    """Tests for _group_by_generation function."""

    def test_group__single_generation__groups_correctly(self):
        """Test grouping records with single generation_id."""
        records: List[Dict[str, Any]] = [
            {
                "id": "1",
                "generation_id": "gen-1",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:00Z",
            },
            {
                "id": "2",
                "generation_id": "gen-1",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:05Z",
            },
        ]

        result = code_agent_trace._group_by_generation(records)

        assert len(result) == 1
        assert "gen-1" in result
        assert len(result["gen-1"]) == 2

    def test_group__multiple_generations__groups_correctly(self):
        """Test grouping records with multiple generation_ids."""
        records: List[Dict[str, Any]] = [
            {
                "id": "1",
                "generation_id": "gen-1",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:00Z",
            },
            {
                "id": "2",
                "generation_id": "gen-2",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:05Z",
            },
            {
                "id": "3",
                "generation_id": "gen-1",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:10Z",
            },
        ]

        result = code_agent_trace._group_by_generation(records)

        assert len(result) == 2
        assert len(result["gen-1"]) == 2
        assert len(result["gen-2"]) == 1

    def test_group__user_message_different_generation__includes_in_other_generation(
        self,
    ):
        """Test that user_message is included in generation without one."""
        records: List[Dict[str, Any]] = [
            {
                "id": "1",
                "generation_id": "gen-user",
                "conversation_id": "conv-1",
                "event": "user_message",
                "timestamp": "2024-01-30T08:00:00Z",
                "data": {"content": "Hello"},
            },
            {
                "id": "2",
                "generation_id": "gen-agent",
                "conversation_id": "conv-1",
                "event": "tool_execution",
                "timestamp": "2024-01-30T08:00:05Z",
                "data": {"tool_type": "shell"},
            },
        ]

        result = code_agent_trace._group_by_generation(records)

        # gen-agent should have the user_message included
        gen_agent_records = result["gen-agent"]
        has_user_message = any(
            r.get("event") == "user_message" for r in gen_agent_records
        )
        assert has_user_message

    def test_group__no_generation_id__creates_standalone(self):
        """Test that records without generation_id get standalone groups."""
        records: List[Dict[str, Any]] = [
            {"id": "1", "event": "tool_execution", "timestamp": "2024-01-30T08:00:00Z"},
        ]

        result = code_agent_trace._group_by_generation(records)

        assert len(result) == 1
        key = list(result.keys())[0]
        assert key.startswith("_standalone_")


class TestFilterByConversation:
    """Tests for _filter_by_conversation function."""

    def test_filter__none__returns_all(self):
        """Test that None filter returns all groups."""
        grouped = {
            "gen-1": [{"conversation_id": "conv-1"}],
            "gen-2": [{"conversation_id": "conv-2"}],
        }

        result = code_agent_trace._filter_by_conversation(grouped, None)

        assert len(result) == 2

    def test_filter__single_conversation__filters_correctly(self):
        """Test filtering by single conversation_id."""
        grouped = {
            "gen-1": [{"conversation_id": "conv-1"}],
            "gen-2": [{"conversation_id": "conv-2"}],
            "gen-3": [{"conversation_id": "conv-1"}],
        }

        result = code_agent_trace._filter_by_conversation(grouped, {"conv-1"})

        assert len(result) == 2
        assert "gen-1" in result
        assert "gen-3" in result
        assert "gen-2" not in result

    def test_filter__multiple_conversations__filters_correctly(self):
        """Test filtering by multiple conversation_ids."""
        grouped = {
            "gen-1": [{"conversation_id": "conv-1"}],
            "gen-2": [{"conversation_id": "conv-2"}],
            "gen-3": [{"conversation_id": "conv-3"}],
        }

        result = code_agent_trace._filter_by_conversation(grouped, {"conv-1", "conv-2"})

        assert len(result) == 2
        assert "gen-3" not in result

    def test_filter__no_matches__returns_empty(self):
        """Test that no matches returns empty dict."""
        grouped = {
            "gen-1": [{"conversation_id": "conv-1"}],
        }

        result = code_agent_trace._filter_by_conversation(grouped, {"conv-999"})

        assert len(result) == 0
