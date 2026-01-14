from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.mcp import ToolSignature
from opik_optimizer.utils.toolcalling.tool_factory import (
    ToolCallingFactory,
    cursor_mcp_config_to_tools,
    resolve_toolcalling_tools,
)


def test_tool_factory__resolves_mcp_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools = [
        {
            "type": "mcp",
            "server_label": "context7",
            "command": "echo",
            "args": [],
            "env": {},
            "allowed_tools": ["get-library-docs"],
        }
    ]

    resolved_tools, function_map = resolve_toolcalling_tools(tools, {})
    assert resolved_tools[0]["function"]["name"] == "get-library-docs"
    assert resolved_tools[0]["function"]["description"] == "mcp tool"
    assert "get-library-docs" in function_map


def test_tool_factory__keeps_pre_resolved_tools(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def _fake_callable(**_kwargs: Any) -> str:
        return "ok"

    monkeypatch.setattr(
        ToolCallingFactory, "_build_callable", lambda *_args, **_kw: _fake_callable
    )

    tool = {
        "type": "function",
        "function": {
            "name": "get-library-docs",
            "description": "existing",
            "parameters": {"type": "object", "properties": {}},
        },
        "mcp": {
            "server_label": "context7",
            "server": {
                "type": "stdio",
                "command": "echo",
                "args": [],
                "env": {},
            },
            "tool": {"name": "get-library-docs"},
        },
    }

    resolved_tools, function_map = resolve_toolcalling_tools([tool], {})
    assert resolved_tools[0]["function"]["description"] == "existing"
    assert "get-library-docs" in function_map


def test_tool_factory__resolves_remote_mcp_tool(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeTool:
        def __init__(self, name: str, description: str, input_schema: dict[str, Any]):
            self.name = name
            self.description = description
            self.inputSchema = input_schema

        def model_dump(self, by_alias: bool = False) -> dict[str, Any]:
            return {
                "name": self.name,
                "description": self.description,
                "inputSchema": self.inputSchema,
                "annotations": {},
            }

    def _fake_list_tools(url: str, headers: dict[str, str]) -> list[FakeTool]:
        assert url == "https://mcp.context7.com/mcp"
        assert headers == {"CONTEXT7_API_KEY": "YOUR_API_KEY"}
        return [
            FakeTool(
                name="get-library-docs",
                description="remote docs tool",
                input_schema={
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                },
            )
        ]

    class FakeResponse:
        def __init__(self, output: str) -> None:
            self.output = output

    def _fake_call_tool(
        url: str,
        headers: dict[str, str],
        tool_name: str,
        arguments: dict[str, Any],
    ) -> FakeResponse:
        assert tool_name == "get-library-docs"
        assert arguments == {"query": "opik"}
        return FakeResponse("docs response")

    monkeypatch.setattr(
        "opik_optimizer.utils.toolcalling.tool_factory.list_tools_from_remote",
        _fake_list_tools,
    )
    monkeypatch.setattr(
        "opik_optimizer.utils.toolcalling.tool_factory.call_tool_from_remote",
        _fake_call_tool,
    )

    tools = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
            "allowed_tools": ["get-library-docs"],
        }
    ]

    prompt = ChatPrompt(system="sys", user="hello", tools=tools)
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    assert resolved_prompt.tools is not None
    assert resolved_prompt.tools[0]["function"]["description"] == "remote docs tool"

    _, function_map = resolve_toolcalling_tools(resolved_prompt.tools, {})
    assert function_map["get-library-docs"](query="opik") == "docs response"


def test_tool_factory__converts_cursor_config() -> None:
    config = {
        "mcpServers": {
            "context7": {
                "url": "https://mcp.context7.com/mcp",
                "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
            }
        }
    }

    tools = cursor_mcp_config_to_tools(config)
    assert tools == [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
        }
    ]
