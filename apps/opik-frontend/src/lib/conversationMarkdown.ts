import isString from "lodash/isString";
import isObject from "lodash/isObject";
import { stripImageTags } from "@/lib/llm";

export interface ConversationMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  tool_calls?: ToolCall[] | string | unknown[];
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

// Flexible tool call type for real-world data
interface FlexibleToolCall {
  id?: string;
  function?: {
    name?: string;
    arguments?: string;
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
 * Formats a tool calls string to be more readable
 * @param toolCallsStr - The tool calls string to format
 * @returns Formatted string with better readability
 */
const formatToolCallsString = (toolCallsStr: string): string => {
  // If it looks like a ValidatorIterator or similar complex object, format it nicely
  if (
    toolCallsStr.includes("ValidatorIterator") ||
    toolCallsStr.includes("UnionValidator")
  ) {
    return `**Tool Call Details:**\n\`\`\`\n${toolCallsStr}\n\`\`\``;
  }

  // If it's a JSON-like string, try to format it
  if (
    toolCallsStr.trim().startsWith("{") ||
    toolCallsStr.trim().startsWith("[")
  ) {
    try {
      const parsed = JSON.parse(toolCallsStr);
      return `**Tool Call Data:**\n\`\`\`json\n${JSON.stringify(
        parsed,
        null,
        2,
      )}\n\`\`\``;
    } catch {
      // If parsing fails, treat as regular string
    }
  }

  // For other strings, wrap in a code block for better readability
  return `**Tool Call:**\n\`\`\`\n${toolCallsStr}\n\`\`\``;
};

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

    // Check if there's any content to display
    const toolCalls =
      role === "assistant" && message.tool_calls ? message.tool_calls : [];
    const hasToolCalls = Array.isArray(toolCalls) && toolCalls.length > 0;

    // Handle case where tool_calls is a string (treat it as additional content)
    let additionalContent = "";
    if (
      role === "assistant" &&
      message.tool_calls &&
      !Array.isArray(message.tool_calls)
    ) {
      const toolCallsStr = isString(message.tool_calls)
        ? message.tool_calls
        : JSON.stringify(message.tool_calls);
      additionalContent = formatToolCallsString(toolCallsStr);
    }

    const contentStr = isString(content) ? content : JSON.stringify(content);
    const combinedContent =
      contentStr + (additionalContent ? "\n\n" + additionalContent : "");
    const hasContent = combinedContent && combinedContent.trim().length > 0;
    const hasAnyContent = hasToolCalls || hasContent;

    // Add collapsible role header (expanded by default)
    lines.push(`<details open>`);
    lines.push(`<summary><strong>${capitalizeFirst(role)}</strong></summary>`);
    lines.push(``);

    if (!hasAnyContent) {
      // Show "No information available" message for empty sections
      lines.push(`*No information available*`);
    } else {
      // Handle tool calls for assistant messages
      if (hasToolCalls) {
        for (const toolCall of toolCalls) {
          // Skip tool calls that don't have a function property at all
          if (!isObject(toolCall) || !(toolCall as FlexibleToolCall).function) {
            continue;
          }

          const flexibleToolCall = toolCall as FlexibleToolCall;
          const functionName =
            flexibleToolCall.function?.name ||
            flexibleToolCall.id ||
            "unknown name";

          lines.push(`<details style="margin-left: 20px;">`);
          lines.push(
            `<summary><strong>Tool call: ${functionName}</strong></summary>`,
          );
          lines.push(``);
          lines.push(`&nbsp;&nbsp;&nbsp;&nbsp;**Function:** ${functionName}`);
          lines.push(
            `&nbsp;&nbsp;&nbsp;&nbsp;**Arguments:** ${
              flexibleToolCall.function?.arguments || "N/A"
            }`,
          );

          // Look for the corresponding tool response
          const toolResponse = conversationData.messages.find(
            (msg) =>
              msg.role === "tool" && msg.tool_call_id === flexibleToolCall.id,
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
      if (hasContent) {
        // Strip image tags, keeping only URLs (images are shown in attachments)
        const processedContentStr = stripImageTags(combinedContent);
        lines.push(processedContentStr.trim());
      }
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

  // Add null checks for safety
  if (!toolCall.function) {
    return "**Function:** Tool call missing required 'function' property";
  }

  lines.push(`**Function:** ${toolCall.function.name || "Unknown"}`);
  lines.push(`**Arguments:** ${toolCall.function.arguments || "N/A"}`);

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
    // Add null checks for safety
    if (!tool.function) {
      lines.push(`**Unknown Tool**`);
      lines.push("Invalid tool call structure");
      lines.push("");
      continue;
    }

    lines.push(`**${tool.function.name || "Unknown"}**`);
    lines.push(tool.function.description || "No description available");
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
