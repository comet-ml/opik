import pytest

from opik_optimizer.utils.toolcalling import mcp_remote
from opik_optimizer.utils.toolcalling.mcp import ToolCallingDependencyError
from opik_optimizer.utils.toolcalling.tool_factory import resolve_toolcalling_tools
from opik_optimizer.utils.toolcalling.tool_factory import cursor_mcp_config_to_tools


@pytest.mark.integration
def test_mcp_remote_live__context7_without_api_key() -> None:
    try:
        tools = mcp_remote.list_tools_from_remote(
            url="https://mcp.context7.com/mcp",
            headers={},
        )
    except ToolCallingDependencyError as exc:
        pytest.skip(f"MCP SDK unavailable: {exc}")

    assert tools, "Expected remote MCP tools to be listed without an API key."
    tool_names = {getattr(tool, "name", None) for tool in tools}
    assert "resolve-library-id" in tool_names

    allowed_tools = ["resolve-library-id", "query-docs"]
    openai_entry = {
        "type": "mcp",
        "server_label": "context7",
        "server_url": "https://mcp.context7.com/mcp",
        "headers": {},
        "allowed_tools": allowed_tools,
    }

    cursor_config = {
        "mcpServers": {
            "context7": {
                "url": "https://mcp.context7.com/mcp",
                "headers": {},
            }
        }
    }
    cursor_tools = cursor_mcp_config_to_tools(cursor_config)
    for tool in cursor_tools:
        tool["allowed_tools"] = allowed_tools

    resolved_openai, _ = resolve_toolcalling_tools([openai_entry], {})
    resolved_cursor, _ = resolve_toolcalling_tools(cursor_tools, {})

    openai_tools_by_name = {
        tool.get("function", {}).get("name"): tool for tool in resolved_openai
    }
    cursor_tools_by_name = {
        tool.get("function", {}).get("name"): tool for tool in resolved_cursor
    }
    assert set(openai_tools_by_name) == set(cursor_tools_by_name)

    for name in sorted(openai_tools_by_name):
        openai_tool = openai_tools_by_name[name]
        cursor_tool = cursor_tools_by_name[name]
        assert openai_tool["function"]["description"] == cursor_tool["function"][
            "description"
        ]
        assert openai_tool["function"]["parameters"] == cursor_tool["function"][
            "parameters"
        ]
        assert openai_tool.get("mcp") is not None
        assert cursor_tool.get("mcp") is not None
        assert openai_tool["mcp"]["server_label"] == cursor_tool["mcp"]["server_label"]
        assert openai_tool["mcp"]["tool"]["name"] == cursor_tool["mcp"]["tool"]["name"]
        assert openai_tool["mcp"]["allowed_tools"] == cursor_tool["mcp"][
            "allowed_tools"
        ]
        assert openai_tool["mcp"]["server"]["type"] == cursor_tool["mcp"]["server"][
            "type"
        ]
        assert openai_tool["mcp"]["server"]["url"] == cursor_tool["mcp"]["server"][
            "url"
        ]

    openai_mcp = [tool.get("mcp") for tool in resolved_openai if tool.get("mcp")]
    cursor_mcp = [tool.get("mcp") for tool in resolved_cursor if tool.get("mcp")]
    assert openai_mcp and cursor_mcp
    assert openai_mcp[0]["server"]["type"] == "remote"
    assert cursor_mcp[0]["server"]["type"] == "remote"
