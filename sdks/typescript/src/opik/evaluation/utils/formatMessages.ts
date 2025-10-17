import type { OpikMessage } from "../models";

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
  return messages.map((msg) => `${msg.role}: ${msg.content}`).join("\n");
}
