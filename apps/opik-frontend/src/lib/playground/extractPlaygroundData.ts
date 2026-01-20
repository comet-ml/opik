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
 * Convert a ProviderMessageType to LLMMessage format
 */
const convertProviderMessageToLLMMessage = (
  msg: ProviderMessageType,
): LLMMessage => {
  return {
    id: generateRandomString(),
    role: msg.role as LLM_MESSAGE_ROLE,
    content: msg.content,
  };
};

/**
 * Check if a value looks like an array of messages (has role and content)
 */
const isMessagesArray = (
  value: unknown,
): value is Array<{ role: string; content: unknown }> => {
  if (!isArray(value) || value.length === 0) return false;

  // Check if first item has role and content properties
  const firstItem = value[0];
  return (
    isObject(firstItem) &&
    "role" in firstItem &&
    "content" in firstItem &&
    typeof firstItem.role === "string"
  );
};

/**
 * Extract messages from trace/span input
 * Handles various input formats:
 * - { messages: [...] } - standard format from playground/LLM calls
 * - Array of messages directly
 * - Plain object/string - converted to a user message
 */
const extractMessagesFromInput = (input: unknown): LLMMessage[] => {
  // Case 1: Input has a messages property with array of messages
  if (isObject(input) && "messages" in input) {
    const messages = (input as { messages: unknown }).messages;
    if (isMessagesArray(messages)) {
      return messages.map((msg) =>
        convertProviderMessageToLLMMessage(msg as ProviderMessageType),
      );
    }
  }

  // Case 2: Input is directly an array of messages
  if (isMessagesArray(input)) {
    return input.map((msg) =>
      convertProviderMessageToLLMMessage(msg as ProviderMessageType),
    );
  }

  // Case 3: Empty array - return empty
  if (isArray(input) && input.length === 0) {
    return [];
  }

  // Case 4: Input is a plain value - convert to user message
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
 * Prefers spans with model/provider information
 */
const findBestLLMSpan = (spans: Span[]): Span | undefined => {
  const llmSpans = spans.filter((s) => s.type === SPAN_TYPE.llm);

  if (llmSpans.length === 0) return undefined;

  // Prefer spans with model information
  const spanWithModel = llmSpans.find((s) => s.model);
  if (spanWithModel) return spanWithModel;

  // Return first LLM span
  return llmSpans[0];
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
 * If spans are provided, tries to find an LLM span with model info
 */
export const extractPlaygroundDataFromTrace = (
  trace: Trace,
  spans?: Span[],
): PlaygroundPrefillData => {
  // Try to find an LLM span with model/provider info
  const llmSpan = spans ? findBestLLMSpan(spans) : undefined;

  if (llmSpan) {
    // Use the LLM span's input and model info
    return extractPlaygroundDataFromSpan(llmSpan);
  }

  // Fall back to trace input
  const messages = extractMessagesFromInput(trace.input);

  return {
    messages,
    // Trace doesn't have model/provider, leave undefined
    model: undefined,
    provider: undefined,
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
  const input = data.input;

  // Check if input exists and is not empty
  if (!input) return false;
  if (isObject(input) && Object.keys(input).length === 0) return false;

  return true;
};
