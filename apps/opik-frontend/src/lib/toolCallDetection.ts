import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

/**
 * Checks if the given data contains tool calls.
 * Tool calls can be present in various formats:
 * - OpenAI format: { role: "assistant", tool_calls: [...] }
 * - Direct messages array: { messages: [{tool_calls: [...]}] }
 * - Output array: { output: [{tool_calls: [...]}] }
 *
 * @param data - The trace input or output data to check
 * @returns true if tool calls are detected, false otherwise
 */
export const hasToolCalls = (data: unknown): boolean => {
  if (!data || !isObject(data)) {
    return false;
  }

  const obj = data as Record<string, unknown>;

  // Direct tool_calls in the root object (OpenAI assistant message format)
  if (
    "tool_calls" in obj &&
    isArray(obj.tool_calls) &&
    obj.tool_calls.length > 0
  ) {
    return true;
  }

  // Check messages array for tool calls
  if ("messages" in obj && isArray(obj.messages)) {
    return obj.messages.some((message: unknown) => {
      if (isObject(message)) {
        const msg = message as Record<string, unknown>;
        return (
          "tool_calls" in msg &&
          isArray(msg.tool_calls) &&
          msg.tool_calls.length > 0
        );
      }
      return false;
    });
  }

  // Check output array for tool calls
  if ("output" in obj && isArray(obj.output)) {
    return obj.output.some((item: unknown) => {
      if (isObject(item)) {
        const outputItem = item as Record<string, unknown>;
        return (
          "tool_calls" in outputItem &&
          isArray(outputItem.tool_calls) &&
          outputItem.tool_calls.length > 0
        );
      }
      return false;
    });
  }

  // Check input array for tool calls
  if ("input" in obj && isArray(obj.input)) {
    return obj.input.some((item: unknown) => {
      if (isObject(item)) {
        const inputItem = item as Record<string, unknown>;
        return (
          "tool_calls" in inputItem &&
          isArray(inputItem.tool_calls) &&
          inputItem.tool_calls.length > 0
        );
      }
      return false;
    });
  }

  // Check for tool role messages (tool responses)
  if ("messages" in obj && isArray(obj.messages)) {
    return obj.messages.some((message: unknown) => {
      if (isObject(message)) {
        const msg = message as Record<string, unknown>;
        return "role" in msg && msg.role === "tool";
      }
      return false;
    });
  }

  return false;
};

/**
 * Checks if a trace contains tool calls in either input or output
 *
 * @param trace - The trace object with input and output fields
 * @returns true if tool calls are detected in input or output
 */
export const traceHasToolCalls = (trace: {
  input?: unknown;
  output?: unknown;
}): boolean => {
  return hasToolCalls(trace.input) || hasToolCalls(trace.output);
};
