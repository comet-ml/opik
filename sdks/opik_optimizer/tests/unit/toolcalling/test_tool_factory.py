from typing import Any

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.mcp import ToolSignature
from opik_optimizer.utils.toolcalling.tool_factory import (
    ToolCallingFactory,
    resolve_toolcalling_tools,
)


def test_tool_factory__resolves_mcp_tools(monkeypatch: Any) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools: list[dict[str, Any]] = [
        {
            "mcp": {
                "name": "context7.get-library-docs",
                "server": {
                    "type": "stdio",
                    "name": "context7-docs",
                    "command": "echo",
                    "args": [],
                    "env": {},
                },
                "tool": {"name": "get-library-docs"},
            }
        }
    ]

    resolved_tools, function_map = resolve_toolcalling_tools(tools, {})
    assert resolved_tools[0]["function"]["name"] == "context7.get-library-docs"
    assert resolved_tools[0]["function"]["description"] == "mcp tool"
    assert "context7.get-library-docs" in function_map

    prompt = ChatPrompt(system="sys", user="hello", tools=tools)
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    assert resolved_prompt.tools is not None
    assert getattr(resolved_prompt, "tools_original") == tools


def test_tool_factory__keeps_pre_resolved_tools(monkeypatch: Any) -> None:
    def _fake_callable(**_kwargs: Any) -> str:
        return "ok"

    monkeypatch.setattr(
        ToolCallingFactory, "_build_callable", lambda *_args, **_kw: _fake_callable
    )

    tool = {
        "type": "function",
        "function": {
            "name": "context7.get-library-docs",
            "description": "existing",
            "parameters": {"type": "object", "properties": {}},
        },
        "mcp": {
            "name": "context7.get-library-docs",
            "server": {
                "type": "stdio",
                "name": "context7-docs",
                "command": "echo",
                "args": [],
                "env": {},
            },
            "tool": {"name": "get-library-docs"},
        },
    }

    resolved_tools, function_map = resolve_toolcalling_tools([tool], {})
    assert resolved_tools[0]["function"]["description"] == "existing"
    assert "context7.get-library-docs" in function_map


def test_tool_factory__avoids_name_collisions(monkeypatch: Any) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
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
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
        },
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "existing",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]

    resolved_tools, _function_map = resolve_toolcalling_tools(tools, {})
    names = [tool.get("function", {}).get("name") for tool in resolved_tools]
    assert "search" in names
    assert "context7.search" in names


def test_tool_factory__resolves_remote_mcp_tool(monkeypatch: Any) -> None:
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
        url: str, headers: dict[str, str], tool_name: str, arguments: dict[str, Any]
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
            "mcp": {
                "name": "context7.get-library-docs",
                "server": {
                    "type": "remote",
                    "url": "https://mcp.context7.com/mcp",
                    "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
                },
                "tool": {"name": "get-library-docs"},
            }
        }
    ]

    prompt = ChatPrompt(system="sys", user="hello", tools=tools)
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    assert resolved_prompt.tools is not None
    assert resolved_prompt.tools[0]["function"]["description"] == "remote docs tool"

    _, function_map = resolve_toolcalling_tools(resolved_prompt.tools, {})
    assert function_map["context7.get-library-docs"](query="opik") == "docs response"
