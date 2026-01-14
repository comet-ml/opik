import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling import cursor_mcp_config_to_tools
from opik_optimizer.utils.toolcalling.mcp import ToolCallingDependencyError
from opik_optimizer.utils.toolcalling.tool_factory import (
    ToolCallingFactory,
    resolve_toolcalling_tools,
)


@pytest.mark.integration
def test_mcp_remote_live__context7_without_api_key() -> None:
    cursor_config = {
        "mcpServers": {
            "context7": {
                "url": "https://mcp.context7.com/mcp",
                "headers": {},
            }
        }
    }
    tools = cursor_mcp_config_to_tools(cursor_config)
    prompt = ChatPrompt(system="System", user="Hello", tools=tools)

    try:
        resolved = ToolCallingFactory().resolve_prompt(prompt)
    except ToolCallingDependencyError as exc:
        pytest.skip(f"MCP SDK unavailable: {exc}")

    assert resolved.tools, "Expected MCP tools to be resolved from remote server."
    resolved_functions = [
        tool.get("function", {})
        for tool in resolved.tools
        if tool.get("type") == "function"
    ]
    tool_names = {tool.get("name") for tool in resolved_functions}
    assert "resolve-library-id" in tool_names
    resolve_tool = next(
        tool for tool in resolved_functions if tool.get("name") == "resolve-library-id"
    )
    assert resolve_tool.get("description")
    assert resolve_tool.get("parameters")

    _, function_map = resolve_toolcalling_tools(resolved.tools, resolved.function_map)
    assert function_map["resolve-library-id"](query="opik")
