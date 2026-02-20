import { PromptType, PromptVariables } from "@/prompt";
import type { OpikMessage } from "../models";
import { OpikMessageContent } from "../models/OpikBaseModel";
import { formatPromptTemplate } from "@/prompt/formatting";
import type {
  TextPart,
  ImagePart,
  FilePart,
  ToolCallPart,
  ToolResultPart,
} from "ai";

type ToolApprovalRequest = {
  type: "tool-approval-request";
};

type ToolApprovalResponse = {
  type: "tool-approval-response";
};

type ReasoningPart = {
  type: "reasoning";
  text?: string;
};

/**
 * Formats an array of OpikMessage objects into a human-readable string.
 *
 * Converts messages from array format to a string where each message is
 * formatted as "role: content" on separate lines. This format is:
 * - Easy for LLM judges to understand and evaluate
 * - Preserves role context (system, user, assistant)
 * - Human-readable for debugging and review
 *
 * @param messages - Array of OpikMessage objects to format
 * @returns Formatted string representation of the messages
 *
 * @example
 * ```typescript
 * const messages = [
 *   { role: 'system', content: 'You are a helpful assistant' },
 *   { role: 'user', content: 'What is the capital of France?' },
 *   { role: 'assistant', content: 'The capital of France is Paris.' }
 * ];
 *
 * const formatted = formatMessagesAsString(messages);
 * // Output:
 * // system: You are a helpful assistant
 * // user: What is the capital of France?
 * // assistant: The capital of France is Paris.
 * ```
 */
export function formatMessagesAsString(messages: OpikMessage[]): string {
  return messages
    .map((msg) => {
      const content = Array.isArray(msg.content)
        ? formatStructuredContentAsString(msg.content)
        : msg.content;
      return `${msg.role}: ${content}`;
    })
    .join("\n");
}

function formatStructuredContentAsString(
  parts: Array<
    | TextPart
    | ImagePart
    | FilePart
    | ToolCallPart
    | ToolResultPart
    | ReasoningPart
    | ToolApprovalRequest
    | ToolApprovalResponse
  >
): string {
  return parts
    .map((part) => {
      if (part.type === "text") {
        return part.text;
      }

      if (part.type === "file") {
        return `[file: ${part.filename || "unknown"}]`;
      }

      if (part.type === "tool-call") {
        return `[tool-call: ${part.toolName}]`;
      }

      if (part.type === "tool-result") {
        return `[tool-result: ${part.toolName}]`;
      }

      if (part.type === "reasoning") {
        return `[reasoning: ${part.text}]`;
      }

      if (part.type === "tool-approval-request") {
        return `[tool-approval-request]`;
      }

      if (part.type === "tool-approval-response") {
        return `[tool-approval-response]`;
      }

      return "";
    })
    .filter((segment) => segment.length > 0)
    .join("\n");
}

/**
 * Interpolates template variables into message content.
 *
 * Handles both simple string content and structured content arrays.
 * For structured content, applies template interpolation to:
 * - TextPart.text fields
 * - ImagePart.image fields (when image is a string URL)
 * - FilePart.data fields (when data is a string URL)
 * - ReasoningPart.text fields
 *
 * @param content - Message content to interpolate
 * @param variables - Variables to substitute into templates
 * @param type - Template engine type (mustache or jinja2)
 * @returns Content with variables interpolated, preserving the input type
 */
export function interpolateMessageContent<T extends OpikMessageContent>(
  content: T,
  variables: PromptVariables,
  type: PromptType
): T {
  if (typeof content === "string") {
    return formatPromptTemplate(content, variables, type) as T;
  }

  // Handle structured content arrays
  if (Array.isArray(content)) {
    const interpolated = content.map((part) => {
      // Interpolate text content
      if (part.type === "text" && typeof part.text === "string") {
        return {
          ...part,
          text: formatPromptTemplate(part.text, variables, type),
        };
      }

      // Interpolate image URL if it's a string
      if (part.type === "image" && typeof part.image === "string") {
        return {
          ...part,
          image: formatPromptTemplate(part.image, variables, type),
        };
      }

      // Interpolate file data URL if it's a string
      if (part.type === "file" && typeof part.data === "string") {
        return {
          ...part,
          data: formatPromptTemplate(part.data, variables, type),
        };
      }

      // Interpolate reasoning text
      if (part.type === "reasoning" && typeof part.text === "string") {
        return {
          ...part,
          text: formatPromptTemplate(part.text, variables, type),
        };
      }

      // Return other parts unchanged (binary data, tool calls, etc.)
      return part;
    });

    return interpolated as T;
  }

  return content;
}

/**
 * Applies template variables to a complete message.
 *
 * Takes a message object and returns a new message with template variables
 * interpolated into the content while preserving the role and other properties.
 *
 * @param message - Message to process
 * @param variables - Variables to substitute into templates
 * @param templateType - Template engine type (mustache or jinja2)
 * @returns New message with interpolated content
 */
export function applyTemplateVariablesToMessage<T extends OpikMessage>(
  message: T,
  variables: PromptVariables,
  templateType: PromptType
): T {
  const interpolatedContent = interpolateMessageContent(
    message.content,
    variables,
    templateType
  );

  return {
    ...message,
    content: interpolatedContent,
  };
}
