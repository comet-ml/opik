import isObject from "lodash/isObject";
import { extractTextFromObject } from "./textExtraction";
import { formatStructuredData } from "./jsonTableUtils";
import { parseArrayFromString } from "./arrayParser";
import { ExtractTextResult } from "./types";

/**
 * Extract text from an array of objects by processing each object
 */
export const extractTextFromArray = (
  arr: unknown[],
): string | ExtractTextResult | undefined => {
  const textItems: string[] = [];
  let hasStructuredObjects = false;

  // First pass: collect tool names from assistant messages with tool_calls
  const toolNames: { [toolCallId: string]: string } = {};
  for (const item of arr) {
    if (
      isObject(item) &&
      "role" in item &&
      (item as Record<string, unknown>).role === "assistant" &&
      "tool_calls" in item
    ) {
      const toolCalls = (item as Record<string, unknown>).tool_calls;
      if (Array.isArray(toolCalls)) {
        for (const toolCall of toolCalls) {
          if (
            typeof toolCall === "object" &&
            toolCall !== null &&
            "id" in (toolCall as Record<string, unknown>) &&
            "function" in (toolCall as Record<string, unknown>)
          ) {
            const toolCallObj = toolCall as Record<string, unknown>;
            const functionObj = toolCallObj.function as Record<string, unknown>;
            if (
              toolCallObj.id &&
              functionObj &&
              "name" in functionObj &&
              functionObj.name
            ) {
              toolNames[toolCallObj.id as string] = functionObj.name as string;
            }
          }
        }
      }
    }
  }

  for (const item of arr) {
    if (typeof item === "string" && item.trim()) {
      textItems.push(item);
    } else if (isObject(item)) {
      // Check if this is a role-based message (like chat messages)
      if (
        "role" in item &&
        "content" in item &&
        typeof (item as Record<string, unknown>).role === "string" &&
        typeof (item as Record<string, unknown>).content === "string"
      ) {
        const role = (item as Record<string, unknown>).role as string;
        const content = (item as Record<string, unknown>).content as string;
        if (content.trim()) {
          // Check if content is JSON
          if (content.trim().startsWith("{") && content.trim().endsWith("}")) {
            try {
              const parsed = JSON.parse(content);
              const formatted = formatStructuredData(parsed);
              // For JSON content, don't show role header - just show the formatted data
              textItems.push(formatted);
            } catch {
              // If JSON parsing fails, treat as regular content
              const roleHeader = role.charAt(0).toUpperCase() + role.slice(1);
              textItems.push(`**${roleHeader}**:\n${content}`);
            }
          } else {
            // Check if content looks like a JavaScript array representation
            const arrayContent = parseArrayFromString(content);
            if (arrayContent) {
              // Extract tool name from the message structure
              let toolName = "unknown_tool";
              if (role === "tool" && "tool_call_id" in item) {
                const toolCallId = (item as Record<string, unknown>)
                  .tool_call_id;
                if (toolCallId && toolNames[toolCallId as string]) {
                  toolName = toolNames[toolCallId as string];
                }
              }

              textItems.push(
                `**Tool call: ${toolName}**\n*Results*:\n${arrayContent}`,
              );
            } else {
              // Format with role header for non-JSON content
              let roleHeader = role.charAt(0).toUpperCase() + role.slice(1);

              // Special handling for tool role - extract tool name
              if (role === "tool" && "tool_call_id" in item) {
                const toolCallId = (item as Record<string, unknown>)
                  .tool_call_id;
                if (toolCallId && toolNames[toolCallId as string]) {
                  roleHeader = `Tool call: ${toolNames[toolCallId as string]}`;
                }
              }

              textItems.push(`**${roleHeader}**:\n${content}`);
            }
          }
        }
      } else {
        // Use regular object extraction
        const extracted = extractTextFromObject(item);
        if (extracted) {
          // Handle structured result
          if (typeof extracted === "object" && "renderType" in extracted) {
            // If we have structured objects that should be rendered as JSON tables,
            // return the entire array as a structured result
            hasStructuredObjects = true;
            // For arrays, we'll convert structured results to text representation
            textItems.push(JSON.stringify(extracted.data, null, 2));
          } else if (typeof extracted === "string") {
            // Handle string result
            textItems.push(extracted);
          }
        }
        // If extracted is undefined (e.g., for assistant messages with tool_calls), skip silently
      }
    }
  }

  // If we have structured objects that should be rendered as JSON tables,
  // return the entire array as a structured result
  if (hasStructuredObjects) {
    return {
      renderType: "json-table",
      data: arr,
    };
  }

  return textItems.length > 0 ? textItems.join("\n\n") : undefined;
};
