import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.ops import toolcalling


def test_toolcalling__prepare_tool_optimization_all_tools() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(prompt, True)
    assert resolved.tools is not None
    assert set(tool_names or []) == {"search", "summarize"}


def test_toolcalling__prepare_tool_optimization_subset() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(
        prompt, {"summarize": True, "search": False}
    )
    assert resolved.tools is not None
    assert tool_names == ["summarize"]


def test_toolcalling__prepare_tool_optimization_rejects_empty_dict() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            }
        ],
    )
    with pytest.raises(ValueError, match="did not enable any tools"):
        toolcalling.prepare_tool_optimization(prompt, {})


def test_toolcalling__prepare_tool_optimization_maps_suffix_name() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "context7.get-library-docs",
                    "description": "docs",
                    "parameters": {"type": "object", "properties": {}},
                },
                "mcp": {
                    "server_label": "context7",
                    "server": {"type": "remote", "url": "https://example.com"},
                    "tool": {"name": "get-library-docs"},
                },
            }
        ],
    )

    _, tool_names = toolcalling.prepare_tool_optimization(
        prompt, {"get-library-docs": True}
    )

    assert tool_names == ["context7.get-library-docs"]


def test_toolcalling__prepare_tool_optimization_rejects_ambiguous_suffix() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "context7.get-library-docs",
                    "description": "docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "alt.get-library-docs",
                    "description": "alt docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            },
        ],
    )

    with pytest.raises(ValueError, match="Ambiguous optimize_tools entries"):
        toolcalling.prepare_tool_optimization(prompt, {"get-library-docs": True})


def test_toolcalling__build_tool_blocks_redacts_sensitive_metadata() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search docs",
                    "parameters": {"type": "object", "properties": {}},
                },
                "mcp": {
                    "server": {
                        "type": "remote",
                        "url": "https://example.com",
                        "headers": {"X": "secret"},
                    },
                    "auth": {"token": "abc"},
                },
            }
        ],
    )

    blocks = toolcalling.build_tool_blocks_from_prompt(prompt)

    assert "***REDACTED***" in blocks
    assert "https://example.com" not in blocks
    assert "secret" not in blocks
