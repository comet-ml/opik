import { Span, Trace, SPAN_TYPE } from "@/types/traces";
import { LLMMessage, LLM_MESSAGE_ROLE, ProviderMessageType } from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";

export interface PlaygroundPrefillData {
  messages: LLMMessage[];
  model?: string;
  provider?: string;
}

/**
 * Map external role strings to valid LLM_MESSAGE_ROLE values
 * Handles roles from various sources (LangChain, OpenAI, etc.)
 */
const mapRoleToLLMMessageRole = (role: string): LLM_MESSAGE_ROLE => {
  const normalizedRole = role.toLowerCase();

  // Direct matches
  if (normalizedRole in LLM_MESSAGE_ROLE) {
    return normalizedRole as LLM_MESSAGE_ROLE;
  }

  // Map common external roles
  switch (normalizedRole) {
    case "human":
      return LLM_MESSAGE_ROLE.user;
    case "tool":
    case "function":
    case "tool_result":
      return LLM_MESSAGE_ROLE.tool_execution_result;
    case "ai":
    case "bot":
      return LLM_MESSAGE_ROLE.assistant;
    default:
      // Default to user for unknown roles
      return LLM_MESSAGE_ROLE.user;
  }
};

/**
 * Get role from a message object, supporting both 'role' and 'type' properties
 * (LangChain/LangGraph uses 'type' instead of 'role')
 */
const getRoleFromMessage = (msg: Record<string, unknown>): string => {
  if (typeof msg.role === "string") {
    return msg.role;
  }
  if (typeof msg.type === "string") {
    return msg.type;
  }
  return "user"; // Default fallback
};

/**
 * Convert a message object to LLMMessage format
 */
const convertToLLMMessage = (msg: Record<string, unknown>): LLMMessage => {
  const role = getRoleFromMessage(msg);
  return {
    id: generateRandomString(),
    role: mapRoleToLLMMessageRole(role),
    content: (msg as ProviderMessageType).content,
  };
};

/**
 * Check if a value looks like an array of messages
 * Supports both 'role' (standard) and 'type' (LangChain) properties
 */
const isMessagesArray = (
  value: unknown,
): value is Array<{ role?: string; type?: string; content: unknown }> => {
  if (!isArray(value)) return false;

  // Empty array is valid (will be handled separately)
  if (value.length === 0) return true;

  // Check if first item has role/type and content properties
  const firstItem = value[0];
  return (
    isObject(firstItem) &&
    ("role" in firstItem || "type" in firstItem) &&
    "content" in firstItem &&
    (typeof (firstItem as Record<string, unknown>).role === "string" ||
      typeof (firstItem as Record<string, unknown>).type === "string")
  );
};

/**
 * Check if input has non-empty extractable content
 */
const hasNonEmptyInput = (input: unknown): boolean => {
  if (!input) return false;

  // Empty object
  if (isObject(input) && Object.keys(input).length === 0) return false;

  // Empty array
  if (isArray(input) && input.length === 0) return false;

  // Object with empty messages array
  if (isObject(input) && "messages" in input) {
    const messages = (input as { messages: unknown }).messages;
    if (isArray(messages) && messages.length === 0) return false;
  }

  return true;
};

/**
 * Extract messages from trace/span input
 * Handles various input formats:
 * - { messages: [...] } - standard format from playground/LLM calls
 * - Array of messages directly
 * - Plain object/string - converted to a user message
 */
const extractMessagesFromInput = (input: unknown): LLMMessage[] => {
  // Case 1: Input has a messages property
  if (isObject(input) && "messages" in input) {
    const messages = (input as { messages: unknown }).messages;
    if (isMessagesArray(messages)) {
      // Handle empty messages array
      if (messages.length === 0) return [];
      return messages.map((msg) =>
        convertToLLMMessage(msg as Record<string, unknown>),
      );
    }
  }

  // Case 2: Input is directly an array of messages
  if (isMessagesArray(input)) {
    // Handle empty array
    if (input.length === 0) return [];
    return input.map((msg) =>
      convertToLLMMessage(msg as Record<string, unknown>),
    );
  }

  // Case 3: Input is a plain value - convert to user message
  const content =
    typeof input === "string" ? input : JSON.stringify(input, null, 2);

  return [
    {
      id: generateRandomString(),
      role: LLM_MESSAGE_ROLE.user,
      content,
    },
  ];
};

/**
 * Find the best LLM span from a list of spans
 * Prefers spans with model/provider information AND non-empty input
 */
const findBestLLMSpan = (spans: Span[]): Span | undefined => {
  const llmSpans = spans.filter((s) => s.type === SPAN_TYPE.llm);

  if (llmSpans.length === 0) return undefined;

  // Prefer spans with model information AND non-empty input
  const spanWithModelAndInput = llmSpans.find(
    (s) => s.model && hasNonEmptyInput(s.input),
  );
  if (spanWithModelAndInput) return spanWithModelAndInput;

  // Then try spans with just non-empty input
  const spanWithInput = llmSpans.find((s) => hasNonEmptyInput(s.input));
  if (spanWithInput) return spanWithInput;

  // Return first LLM span with model (even if empty input, for model info)
  const spanWithModel = llmSpans.find((s) => s.model);
  if (spanWithModel) return spanWithModel;

  return undefined;
};

/**
 * Extract playground prefill data from a span
 */
export const extractPlaygroundDataFromSpan = (
  span: Span,
): PlaygroundPrefillData => {
  const messages = extractMessagesFromInput(span.input);

  return {
    messages,
    model: span.model,
    provider: span.provider,
  };
};

/**
 * Extract playground prefill data from a trace
 * If spans are provided, tries to find an LLM span with model info and non-empty input
 */
export const extractPlaygroundDataFromTrace = (
  trace: Trace,
  spans?: Span[],
): PlaygroundPrefillData => {
  // Try to find an LLM span with model/provider info and non-empty input
  const llmSpan = spans ? findBestLLMSpan(spans) : undefined;

  // Only use LLM span if it has non-empty input
  if (llmSpan && hasNonEmptyInput(llmSpan.input)) {
    return extractPlaygroundDataFromSpan(llmSpan);
  }

  // Fall back to trace input, but keep model/provider from LLM span if available
  const messages = extractMessagesFromInput(trace.input);

  return {
    messages,
    model: llmSpan?.model,
    provider: llmSpan?.provider,
  };
};

/**
 * Extract playground prefill data from either a trace or span
 * Automatically determines the best source for messages and model info
 */
export const extractPlaygroundData = (
  data: Trace | Span,
  allData?: Array<Trace | Span>,
): PlaygroundPrefillData => {
  const isSpan = "trace_id" in data && "type" in data;

  if (isSpan) {
    return extractPlaygroundDataFromSpan(data as Span);
  }

  // For traces, also pass any available spans
  const spans = allData?.filter(
    (item): item is Span => "trace_id" in item && "type" in item,
  );

  return extractPlaygroundDataFromTrace(data as Trace, spans);
};

/**
 * Check if the data has extractable messages for playground
 */
export const canOpenInPlayground = (data: Trace | Span): boolean => {
  return hasNonEmptyInput(data.input);
};
