from typing import Any, cast

import pytest

from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class TestChatPromptWithTools:
    """Tests for ChatPrompt with tools."""

    def test_stores_tools(self) -> None:
        """Should store tools when provided."""
        tools = [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search the web",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                        "required": ["query"],
                    },
                },
            }
        ]
        prompt = ChatPrompt(system="Use tools", tools=tools)

        assert prompt.tools == tools

    def test_wraps_function_map_with_track(self) -> None:
        """Should wrap function_map functions with Opik track."""

        def my_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tool})

        assert "my_tool" in prompt.function_map

    def test_preserves_already_tracked_functions(self) -> None:
        """Should preserve functions that are already tracked."""
        from opik import track

        @track(type="tool")
        def my_tracked_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tracked_tool})

        assert prompt.function_map["my_tool"] is my_tracked_tool

    def test_accepts_mcp_stdio_tool(self) -> None:
        prompt = ChatPrompt(
            system="System",
            tools=[
                {
                    "mcp": {
                        "name": "context7_docs",
                        "server": {
                            "type": "stdio",
                            "command": "npx",
                            "args": [],
                            "env": {},
                        },
                        "tool": {"name": "get-library-docs"},
                    }
                }
            ],
        )
        assert isinstance(prompt.tools, list)
        tool = prompt.tools[0]
        assert "mcp" in tool
        assert tool["mcp"]["name"] == "context7_docs"
        assert tool["mcp"]["server"]["type"] == "stdio"
        assert tool["mcp"]["server"]["command"] == "npx"
        assert tool["mcp"]["tool"]["name"] == "get-library-docs"

    def test_accepts_mcp_remote_tool(self) -> None:
        prompt = ChatPrompt(
            system="System",
            tools=[
                {
                    "mcp": {
                        "name": "remote_docs",
                        "server": {
                            "type": "remote",
                            "url": "https://mcp.example.com",
                        },
                        "tool": {"name": "search-docs"},
                    }
                }
            ],
        )
        assert isinstance(prompt.tools, list)
        tool = prompt.tools[0]
        assert "mcp" in tool
        assert tool["mcp"]["name"] == "remote_docs"
        assert tool["mcp"]["server"]["type"] == "remote"
        assert tool["mcp"]["server"]["url"] == "https://mcp.example.com"
        assert tool["mcp"]["tool"]["name"] == "search-docs"

    def test_accepts_cursor_mcp_config_as_tools(self) -> None:
        prompt = ChatPrompt(
            system="System",
            tools={
                "mcpServers": {
                    "context7": {
                        "url": "https://mcp.context7.com/mcp",
                        "headers": {"CONTEXT7_API_KEY": "test-key"},
                    }
                }
            },
        )
        assert isinstance(prompt.tools, list)
        assert prompt.tools[0]["type"] == "mcp"
        assert prompt.tools[0]["server_label"] == "context7"
        assert prompt.tools[0]["server_url"] == "https://mcp.context7.com/mcp"

    def test_rejects_invalid_cursor_config(self) -> None:
        with pytest.raises(ValueError, match="mcpServers"):
            ChatPrompt(system="System", tools={"wrong_key": {}})

    def test_rejects_non_callable_function_map_values(self) -> None:
        with pytest.raises(ValueError, match="callable"):
            ChatPrompt(
                system="System",
                function_map={"search": cast(Any, "not-callable")},
            )
