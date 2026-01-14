"""Prompt templates for MCP tool guidance and system scaffolding."""

from __future__ import annotations

import textwrap


PROMPT_TOOL_HEADER = "<<TOOL_DESCRIPTION>>"
PROMPT_TOOL_FOOTER = "<<END_TOOL_DESCRIPTION>>"

# System-prompt scaffolding below is inspired by the MCP section of Cline's
# system prompt (Apache-2.0). See https://github.com/cline/cline for details.
TOOL_USE_GUIDELINES = textwrap.dedent(
    """
    # Tool Use Guidelines

    1. In <thinking> tags, decide what you already know and what information you still need.
    2. Choose the best tool for the current step using the descriptions and schemas provided.
    3. Use one tool call per message, wait for its result, then decide the next step.
    4. Format tool calls exactly with the XML shown in the tool examples.
    5. After each tool call, read the result carefully before responding or calling another tool.
    6. Always incorporate the tool output into your final answer.
    """
).strip()


MCP_SYSTEM_BODY_TEMPLATE = textwrap.dedent(
    """
    You are an assistant that answers developer questions using the available MCP tool.
    Always decide whether the tool is required before answering.
    Always call the tool at least once before replying and incorporate the returned documentation into your answer (quote key terms, mention the library ID).

    Tool description:
    {tool_header}
    {description}
    {tool_footer}

    Tool parameters:
    {parameter_section}
    When you call the tool, read its response carefully before replying.
    """
).strip()


MCP_PROMPT_DEFAULTS = {
    "mcp_tool_header": PROMPT_TOOL_HEADER,
    "mcp_tool_footer": PROMPT_TOOL_FOOTER,
    "mcp_tool_use_guidelines": TOOL_USE_GUIDELINES,
    "mcp_system_body": MCP_SYSTEM_BODY_TEMPLATE,
}


def build_mcp_header(
    manifest_name: str,
    command_line: str,
    signature_name: str,
    signature_description: str,
    schema_block: str,
) -> str:
    """Build the MCP server header block for system prompts."""
    return textwrap.dedent(
        f"""
        MCP SERVERS

        The Model Context Protocol (MCP) enables communication between the system and locally running MCP servers that provide additional tools and resources to extend your capabilities.

        # Connected MCP Servers

        When a server is connected, you can use the server's tools via the `use_mcp_tool` tool, and access the server's resources via the `access_mcp_resource` tool.

        ## {manifest_name} (`{command_line}`)

        ### Available Tools
        - {signature_name}: {signature_description}
            Input Schema:
        {schema_block}
        """
    ).strip()


def build_mcp_body(description: str, parameter_section: str) -> str:
    """Build the MCP system prompt body for a single tool."""
    return MCP_SYSTEM_BODY_TEMPLATE.format(
        tool_header=PROMPT_TOOL_HEADER,
        tool_footer=PROMPT_TOOL_FOOTER,
        description=description,
        parameter_section=parameter_section,
    ).strip()
