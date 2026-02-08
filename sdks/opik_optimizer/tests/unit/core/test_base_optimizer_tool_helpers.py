"""Unit tests for optimizer tool helper utilities."""

from __future__ import annotations

from typing import Any

from opik_optimizer.utils import tool_helpers as tool_utils


class TestDeepMergeDicts:
    """Tests for utils.tool_helpers.deep_merge_dicts."""

    def test_merges_flat_dicts(self) -> None:
        """Should merge two flat dictionaries."""
        base = {"a": 1, "b": 2}
        overrides = {"b": 3, "c": 4}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"a": 1, "b": 3, "c": 4}

    def test_deep_merges_nested_dicts(self) -> None:
        """Should recursively merge nested dictionaries."""
        base = {"level1": {"a": 1, "b": 2}, "other": "value"}
        overrides = {"level1": {"b": 3, "c": 4}}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"level1": {"a": 1, "b": 3, "c": 4}, "other": "value"}

    def test_override_replaces_non_dict_with_dict(self) -> None:
        """Should replace non-dict value with dict value."""
        base = {"key": "string_value"}
        overrides = {"key": {"nested": "value"}}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"key": {"nested": "value"}}

    def test_override_replaces_dict_with_non_dict(self) -> None:
        """Should replace dict value with non-dict value."""
        base = {"key": {"nested": "value"}}
        overrides = {"key": "string_value"}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"key": "string_value"}

    def test_does_not_modify_original_dicts(self) -> None:
        """Should not modify the input dictionaries."""
        base = {"a": {"b": 1}}
        overrides = {"a": {"c": 2}}

        tool_utils.deep_merge_dicts(base, overrides)

        assert base == {"a": {"b": 1}}
        assert overrides == {"a": {"c": 2}}

    def test_handles_empty_base(self) -> None:
        """Should handle empty base dictionary."""
        result = tool_utils.deep_merge_dicts({}, {"a": 1})
        assert result == {"a": 1}

    def test_handles_empty_overrides(self) -> None:
        """Should handle empty overrides dictionary."""
        result = tool_utils.deep_merge_dicts({"a": 1}, {})
        assert result == {"a": 1}


class TestSerializeTools:
    """Tests for utils.tool_helpers.serialize_tools."""

    def test_serializes_tools_list(self, chat_prompt_with_tools: Any) -> None:
        """Should return deep copy of tools list."""
        result = tool_utils.serialize_tools(chat_prompt_with_tools)

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["function"]["name"] == "search"

    def test_returns_empty_list_when_no_tools(self, simple_chat_prompt: Any) -> None:
        """Should return empty list when prompt has no tools."""
        result = tool_utils.serialize_tools(simple_chat_prompt)

        assert result == []

    def test_returns_deep_copy(self, chat_prompt_with_tools: Any) -> None:
        """Should return a deep copy, not reference original."""
        result = tool_utils.serialize_tools(chat_prompt_with_tools)

        # Modify the result
        result[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert chat_prompt_with_tools.tools[0]["function"]["name"] == "search"
