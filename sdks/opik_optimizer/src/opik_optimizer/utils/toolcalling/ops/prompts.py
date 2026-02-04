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


TOOL_DESCRIPTION_SYSTEM_PROMPT_TEMPLATE = textwrap.dedent(
    """You are an expert prompt engineer. Your task is to improve tool descriptions for LLM tool calling.

        Focus on making the tool description more effective by:
        1. Clearly stating when the tool should be used
        2. Explicitly listing required and optional arguments
        3. Describing how tool outputs must be used in the final answer
        4. Keeping the description concise but unambiguous

        CRITICAL CONSTRAINTS:
        1. Do NOT add or remove tools.
        2. Do NOT change tool names or parameter schemas.
        3. Only update tool descriptions and parameter descriptions.

        Return a JSON object with this structure:
        {
          "prompts": [
            {
              "tool_descriptions": [
                {"name": "tool_name", "description": "new description"},
                {"name": "other_tool", "description": "new description"}
              ],
              "parameter_descriptions": [
                {
                  "tool_name": "tool_name",
                  "parameters": [
                    {"name": "param", "description": "new param description"}
                  ]
                }
              ],
              "improvement_focus": "...",
              "reasoning": "..."
            }
          ]
        }

        All keys are required. If you have no improvement_focus or reasoning, return
        an empty string."""
).strip()


TOOL_DESCRIPTION_USER_PROMPT_TEMPLATE = textwrap.dedent(
    """Current tools:
{tool_blocks}

Current best score: {best_score:.4f}
{history_context}

Generate [{prompts_per_round}] improved descriptions for the tools listed above.
Each description should clarify expected input arguments and set explicit expectations
for how the tool output must be used in the final response.
You may also improve parameter descriptions to be concise and unambiguous.
Avoid changing unrelated parts of the prompt. Focus only on tool and parameter descriptions.

Return a JSON object of the form:
{{
  "prompts": [
    {{
      "tool_descriptions": [
        {{"name": "tool_name", "description": "new description"}},
        {{"name": "other_tool", "description": "new description"}}
      ],
      "parameter_descriptions": [
        {{
          "tool_name": "tool_name",
          "parameters": [
            {{"name": "param", "description": "new param description"}}
          ]
        }}
      ],
      "improvement_focus": "...",
      "reasoning": "..."
    }}
  ]
}}

All keys are required. If you have no improvement_focus or reasoning, return
an empty string.
"""
).strip()


def build_tool_description_system_prompt(
    *,
    template: str | None = None,
) -> str:
    """Build the system prompt for tool description optimization."""
    return (template or TOOL_DESCRIPTION_SYSTEM_PROMPT_TEMPLATE).strip()


def build_tool_description_user_prompt(
    tool_blocks: str,
    best_score: float,
    history_context: str,
    prompts_per_round: int,
    *,
    template: str | None = None,
) -> str:
    """Build the user prompt for generating improved tool descriptions."""
    template = template or TOOL_DESCRIPTION_USER_PROMPT_TEMPLATE
    return template.format(
        tool_blocks=tool_blocks,
        best_score=best_score,
        history_context=history_context,
        prompts_per_round=prompts_per_round,
    )


MCP_PROMPT_DEFAULTS = {
    "mcp_tool_header": PROMPT_TOOL_HEADER,
    "mcp_tool_footer": PROMPT_TOOL_FOOTER,
    "mcp_tool_use_guidelines": TOOL_USE_GUIDELINES,
    "mcp_system_body": MCP_SYSTEM_BODY_TEMPLATE,
    "tool_description_system": TOOL_DESCRIPTION_SYSTEM_PROMPT_TEMPLATE,
    "tool_description_user": TOOL_DESCRIPTION_USER_PROMPT_TEMPLATE,
}
