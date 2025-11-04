"""
Example OptimizableAgent that connects to running MCP servers.

This example demonstrates how to create an OptimizableAgent that can
communicate with MCP (Model Context Protocol) servers to access external
tools and resources.

To use this example:
1. Use create_mcp_prompt() to create a ChatPrompt with MCP tools configured
2. Pass that ChatPrompt to MCPAgent
3. The agent will handle MCP tool calls automatically
"""

from typing import Any, cast
from collections.abc import Callable

from opik_optimizer import OptimizableAgent, ChatPrompt
from opik_optimizer.mcp_utils.mcp import (
    MCPManifest,
    ToolSignature,
    list_tools_from_manifest,
)
from opik_optimizer.mcp_utils.mcp_workflow import MCPToolInvocation


def create_mcp_prompt(
    mcp_server: dict[str, Any],
    tool_names: list[str] | None = None,
    system_prompt: str | None = None,
    user_template: str = "{query}",
    model: str = "gpt-4o-mini",
) -> ChatPrompt:
    """Create a ChatPrompt configured with MCP tools.

    This helper function discovers MCP tools and creates a ChatPrompt
    with them registered in the function_map.

    Args:
        mcp_server: dict[str, Any]
            Dictionary describing the MCP server configuration. Must contain the following fields:
                - name (str): Name of the MCP server.
                - command (str): Command to start the MCP server (e.g., "npx").
                - args (list[str]): Arguments to pass to the command (e.g., ["-y", "@my-org/mcp-server"]).
                - env (dict[str, str]): Environment variables for the MCP server process.
        tool_names: List of tool names to load from the MCP server.
            If None, all available tools will be loaded.
        system_prompt: Optional custom system prompt. If not provided,
            a default prompt will be generated.
        user_template: Template for user messages (default: "{query}")
        model: LLM model to use (default: "gpt-4o-mini")

    Returns:
        ChatPrompt configured with MCP tools

    Example:
        ```python
        from scripts.llm_frameworks.mcp.mcp_agent import create_mcp_prompt, MCPAgent

        # Configure your MCP server as a dictionary
        mcp_server = {
            "name": "my-mcp-server",
            "command": "npx",
            "args": ["-y", "@my-org/mcp-server"],
            "env": {}
        }

        # Create prompt with MCP tools
        prompt = create_mcp_prompt(mcp_server, tool_names=["my-tool"])

        # Create agent with the prompt
        agent = MCPAgent(prompt, project_name="mcp-example")

        # Use the agent
        result = agent.invoke([{"role": "user", "content": "What can you do?"}])
        ```
    """
    manifest = MCPManifest.from_dict(mcp_server)

    # Discover available tools from the MCP server (single connection)
    available_tools = list_tools_from_manifest(manifest)

    # Build a lookup dictionary of tools by name - handle both Pydantic models and dicts
    tools_by_name = {}
    tool_list = []
    for tool in available_tools:
        name = None
        tool_entry = None

        if hasattr(tool, "name"):
            name = tool.name
            if hasattr(tool, "model_dump"):
                tool_entry = tool.model_dump(by_alias=True)
        elif hasattr(tool, "model_dump"):
            tool_entry = tool.model_dump(by_alias=True)
            name = tool_entry.get("name")
        elif isinstance(tool, dict):
            tool_entry = tool
            name = tool.get("name")

        if name and tool_entry:
            tools_by_name[name] = tool_entry
            tool_list.append(name)

    # Filter to requested tools or use all available
    if tool_names is None:
        tool_names = tool_list
    else:
        # Validate requested tools exist
        missing_tools = set(tool_names) - set(tool_list)
        if missing_tools:
            raise ValueError(
                f"Tools not found in MCP server: {missing_tools}. "
                f"Available tools: {tool_list}"
            )

    # Load tool signatures directly from already-fetched tools (no additional connections!)
    tool_signatures = {}
    tool_invocations = {}
    for tool_name in tool_names:
        tool_entry = tools_by_name[tool_name]
        # Convert tool entry to ToolSignature format (matching load_tool_signature_from_manifest logic)
        annotations = tool_entry.get("annotations") or {}
        examples = annotations.get("examples")
        signature = ToolSignature.from_tool_entry(
            {
                "type": "function",
                "function": {
                    "name": tool_entry.get("name", tool_name),
                    "description": tool_entry.get("description", ""),
                    "parameters": tool_entry.get("inputSchema", {}),
                    "examples": examples,
                },
            }
        )
        tool_signatures[tool_name] = signature
        tool_invocations[tool_name] = MCPToolInvocation(
            manifest=manifest,
            tool_name=tool_name,
        )

    # Generate system prompt if not provided
    if system_prompt is None:
        tool_descriptions = "\n".join(
            f"- {name}: {sig.description}" for name, sig in tool_signatures.items()
        )
        system_prompt = f"""You are a helpful assistant with access to MCP tools from the {manifest.name} server.

Available tools:
{tool_descriptions}

Use these tools when appropriate to help answer user questions."""

    # Create ChatPrompt with MCP tools
    tools = [sig.to_tool_entry() for sig in tool_signatures.values()]

    return ChatPrompt(
        system=system_prompt,
        user=user_template,
        tools=tools,
        function_map=cast(dict[str, Callable[..., Any]], tool_invocations),
        model=model,
    )


class MCPAgent(OptimizableAgent):
    """An OptimizableAgent that can communicate with MCP servers.

    This agent follows the standard OptimizableAgent pattern. Use
    create_mcp_prompt() to create a ChatPrompt with MCP tools configured,
    then pass it to this agent.

    Example:
        ```python
        from scripts.llm_frameworks.mcp.mcp_agent import create_mcp_prompt, MCPAgent

        # Configure your MCP server as a dictionary
        manifest = {
            "name": "my-mcp-server",
            "command": "npx",
            "args": ["-y", "@my-org/mcp-server"],
            "env": {}
        }

        # Create prompt with MCP tools
        prompt = create_mcp_prompt(manifest, tool_names=["my-tool"])

        # Create agent
        agent = MCPAgent(prompt, project_name="mcp-example")

        # Use the agent
        result = agent.invoke([{"role": "user", "content": "What can you do?"}])
        ```
    """

    def init_agent(self, prompt: ChatPrompt) -> None:
        """Initialize the agent with the MCP-enabled prompt.

        Args:
            prompt: ChatPrompt with MCP tools in function_map
        """
        self.prompt = prompt
        # Set model from prompt for LLM calls
        self.model = prompt.model
        self.model_kwargs = prompt.model_kwargs

        # Extract MCP tool information for convenience methods
        self.tool_invocations = {}
        self.tool_signatures = {}
        if prompt.function_map:
            for tool_name, func in prompt.function_map.items():
                if isinstance(func, MCPToolInvocation):
                    self.tool_invocations[tool_name] = func
                    # Try to extract signature from tools if available
                    if prompt.tools:
                        for tool_entry in prompt.tools:
                            if isinstance(tool_entry, dict):
                                func_entry = tool_entry.get("function", {})
                                if func_entry.get("name") == tool_name:
                                    # Store basic info (full signature would need ToolSignature)
                                    self.tool_signatures[tool_name] = {
                                        "name": func_entry.get("name"),
                                        "description": func_entry.get(
                                            "description", ""
                                        ),
                                        "parameters": func_entry.get("parameters", {}),
                                    }
