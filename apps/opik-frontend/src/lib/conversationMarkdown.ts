import isString from "lodash/isString";
import isObject from "lodash/isObject";
import { stripImageTags } from "@/lib/llm";

export interface ConversationMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
}

export interface ToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
}

export interface ConversationData {
  model?: string;
  messages: ConversationMessage[];
  tools?: Tool[];
  kwargs?: Record<string, unknown>;
}

export interface Tool {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: {
      type: string;
      properties: Record<string, unknown>;
      required: string[];
    };
  };
}

/**
 * Converts a conversation JSON object into markdown format
 * @param conversationData - The conversation data object
 * @param options - Optional formatting options
 * @returns Formatted markdown string
 */
export const convertConversationToMarkdown = (
  conversationData: ConversationData,
  options: {
    includeModel?: boolean;
    includeTools?: boolean;
    includeKwargs?: boolean;
  } = {},
): string => {
  if (
    !conversationData?.messages ||
    !Array.isArray(conversationData.messages)
  ) {
    return "# Error\nInvalid conversation data provided.";
  }

  const { includeTools = false, includeKwargs = false } = options;

  const lines: string[] = [];

  // Model section removed per user request

  // Add tools section if requested (expanded by default)
  if (
    includeTools &&
    conversationData.tools &&
    conversationData.tools.length > 0
  ) {
    lines.push(`<details open>`);
    lines.push(`<summary><strong>Available Tools</strong></summary>`);
    lines.push(``);
    lines.push(formatTools(conversationData.tools));
    lines.push(`</details>`);
    lines.push(`<div style="height: 1px; margin: 4px 0;"></div>`); // Visible spacing element
  }

  // Add kwargs section if requested (expanded by default)
  if (includeKwargs && conversationData.kwargs) {
    lines.push(`<details open>`);
    lines.push(`<summary><strong>Configuration</strong></summary>`);
    lines.push(``);
    lines.push("```json");
    lines.push(JSON.stringify(conversationData.kwargs, null, 2));
    lines.push("```");
    lines.push(`</details>`);
    lines.push(`<div style="height: 1px; margin: 4px 0;"></div>`); // Visible spacing element
  }

  // Process each message
  for (const message of conversationData.messages) {
    if (!isObject(message) || !isString(message.role)) {
      continue;
    }

    const role = message.role;
    const content = message.content || "";

    // Skip tool role messages entirely (they're now included in tool call sections)
    if (role === "tool") {
      continue;
    }

    // Add collapsible role header (expanded by default)
    lines.push(`<details open>`);
    lines.push(`<summary><strong>${capitalizeFirst(role)}</strong></summary>`);
    lines.push(``);

    // Handle tool calls for assistant messages
    if (
      role === "assistant" &&
      message.tool_calls &&
      message.tool_calls.length > 0
    ) {
      for (const toolCall of message.tool_calls) {
        lines.push(`<details style="margin-left: 20px;">`);
        lines.push(
          `<summary><strong>Tool call: ${toolCall.function.name}</strong></summary>`,
        );
        lines.push(``);
        lines.push(
          `&nbsp;&nbsp;&nbsp;&nbsp;**Function:** ${toolCall.function.name}`,
        );
        lines.push(
          `&nbsp;&nbsp;&nbsp;&nbsp;**Arguments:** ${toolCall.function.arguments}`,
        );

        // Look for the corresponding tool response
        const toolResponse = conversationData.messages.find(
          (msg) => msg.role === "tool" && msg.tool_call_id === toolCall.id,
        );

        if (toolResponse && toolResponse.content) {
          const toolResponseContent = isString(toolResponse.content)
            ? toolResponse.content
            : JSON.stringify(toolResponse.content);
          lines.push(``);
          lines.push(`&nbsp;&nbsp;&nbsp;&nbsp;**Response:**`);
          lines.push(`&nbsp;&nbsp;&nbsp;&nbsp;${toolResponseContent.trim()}`);
        }

        lines.push(`</details>`);
      }

      // Add spacing after all tool calls for this assistant message
      lines.push(`<div style="height: 1px; margin: 4px 0;"></div>`); // Visible spacing element
    }

    // Add content if present
    let contentStr = isString(content) ? content : JSON.stringify(content);
    if (contentStr.trim()) {
      // Strip image tags, keeping only URLs (images are shown in attachments)
      contentStr = stripImageTags(contentStr);
      lines.push(contentStr.trim());
    }

    // Close the role section
    lines.push(`</details>`);
    lines.push(`<div style="height: 1px; margin: 4px 0;"></div>`); // Visible spacing element
  }

  return lines.join("\n");
};

/**
 * Capitalizes the first letter of a string
 * @param str - The string to capitalize
 * @returns Capitalized string
 */
const capitalizeFirst = (str: string): string => {
  if (!str) return str;
  return str.charAt(0).toUpperCase() + str.slice(1);
};

/**
 * Formats a tool call for display in markdown
 * @param toolCall - The tool call object
 * @returns Formatted tool call string
 */
export const formatToolCall = (toolCall: ToolCall): string => {
  const lines: string[] = [];

  lines.push(`**Function:** ${toolCall.function.name}`);
  lines.push(`**Arguments:** ${toolCall.function.arguments}`);

  return lines.join("\n");
};

/**
 * Formats tool definitions for display in markdown
 * @param tools - Array of tool definitions
 * @returns Formatted tools string
 */
export const formatTools = (tools: Tool[]): string => {
  if (!tools || tools.length === 0) {
    return "No tools available.";
  }

  const lines: string[] = [];

  for (const tool of tools) {
    lines.push(`**${tool.function.name}**`);
    lines.push(tool.function.description);
    lines.push("");

    if (tool.function.parameters) {
      lines.push("**Parameters:**");
      lines.push("```json");
      lines.push(JSON.stringify(tool.function.parameters, null, 2));
      lines.push("```");
      lines.push("");
    }
  }

  return lines.join("\n");
};
