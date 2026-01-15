import type { ChatMessage } from "../types";

/**
 * Formats chat messages for human-readable comparison in diffs.
 * Handles different content types intelligently:
 * - Text and URL content: full content shown with all properties
 * - Unrecognized types: simplified placeholder
 */
export function formatChatMessagesForComparison(
  messages: ChatMessage[],
): string {
  const lines: string[] = [];

  for (let i = 0; i < messages.length; i++) {
    const message = messages[i];
    lines.push(`Message ${i + 1} [${message.role}]:`);

    if (typeof message.content === "string") {
      // Simple string content
      lines.push(indentContent(message.content));
    } else if (Array.isArray(message.content)) {
      // Multimodal content
      for (let j = 0; j < message.content.length; j++) {
        const part = message.content[j];
        lines.push(`  Part ${j + 1}:`);
        lines.push(`    Type: ${part.type}`);

        if (part.type === "text" && "text" in part) {
          // Text content - show the text and any additional properties
          const textPart = part as Record<string, unknown>;
          lines.push(indentContent(String(textPart.text), 4));
          formatAdditionalProperties(textPart, ["type", "text"], lines, 4);
        } else if (part.type === "image_url" && "image_url" in part) {
          // Image URL - show all properties
          const imagePart = part as Record<string, unknown>;
          const imageUrl = imagePart.image_url as Record<string, unknown>;
          lines.push(`    URL: ${imageUrl.url}`);
          formatObjectProperties(imageUrl, ["url"], lines, 4);
          formatAdditionalProperties(imagePart, ["type", "image_url"], lines, 4);
        } else if (part.type === "video_url" && "video_url" in part) {
          // Video URL - show all properties
          const videoPart = part as Record<string, unknown>;
          const videoUrl = videoPart.video_url as Record<string, unknown>;
          lines.push(`    URL: ${videoUrl.url}`);
          formatObjectProperties(videoUrl, ["url"], lines, 4);
          formatAdditionalProperties(videoPart, ["type", "video_url"], lines, 4);
        } else {
          // Unrecognized content type - show simplified message
          lines.push(`    [Difference found in unrecognized content type]`);
        }
      }
    }

    // Add blank line between messages for readability
    if (i < messages.length - 1) {
      lines.push("");
    }
  }

  return lines.join("\n");
}

/**
 * Format additional properties of an object (excluding specified keys)
 */
function formatAdditionalProperties(
  obj: Record<string, unknown>,
  excludeKeys: string[],
  lines: string[],
  indent: number = 4,
): void {
  const additionalKeys = Object.keys(obj).filter(
    (key) => !excludeKeys.includes(key),
  );
  if (additionalKeys.length > 0) {
    for (const key of additionalKeys.sort()) {
      const value = obj[key];
      lines.push(`${" ".repeat(indent)}${key}: ${formatValue(value)}`);
    }
  }
}

/**
 * Format properties of an object (excluding specified keys)
 */
function formatObjectProperties(
  obj: Record<string, unknown>,
  excludeKeys: string[],
  lines: string[],
  indent: number = 4,
): void {
  const keys = Object.keys(obj).filter((key) => !excludeKeys.includes(key));
  if (keys.length > 0) {
    for (const key of keys.sort()) {
      const value = obj[key];
      lines.push(`${" ".repeat(indent)}${key}: ${formatValue(value)}`);
    }
  }
}

/**
 * Format a value for display (handles objects, arrays, primitives)
 */
function formatValue(value: unknown): string {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean")
    return String(value);
  if (Array.isArray(value)) return JSON.stringify(value);
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

/**
 * Indent content for better readability in diffs
 */
function indentContent(content: string, spaces: number = 2): string {
  const indent = " ".repeat(spaces);
  return content
    .split("\n")
    .map((line) => `${indent}${line}`)
    .join("\n");
}
