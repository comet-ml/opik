import pytest

from opik_optimizer.utils.toolcalling import mcp_remote
from opik_optimizer.utils.toolcalling.mcp import ToolCallingDependencyError


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
