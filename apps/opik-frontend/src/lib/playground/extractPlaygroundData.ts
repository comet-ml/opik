import { Span, Trace, SPAN_TYPE } from "@/types/traces";
import {
  LLMMessage,
  LLM_MESSAGE_ROLE,
  ProviderMessageType,
  MessageSourceAnnotation,
  MessageSourceType,
} from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";

/**
 * Simplified span info for trace context display
 */
export interface TraceContextSpan {
  id: string;
  name: string;
  type: SPAN_TYPE | "trace";
  parentSpanId?: string;
  isSource: boolean; // Whether this is the span being edited
  model?: string;
  provider?: string;
  duration?: number;
  totalTokens?: number;
}

/**
 * Source span metadata for the playground
 */
export interface SourceSpanInfo {
  id: string;
  name: string;
  type: SPAN_TYPE;
  model?: string;
  provider?: string;
  duration?: number;
  totalTokens?: number;
}

/**
 * Trace context for understanding where playground data came from
 */
export interface PlaygroundTraceContext {
  traceId: string;
  traceName: string;
  projectId: string;
  projectName?: string;
  // The span this was extracted from (undefined if from trace directly)
  sourceSpan?: SourceSpanInfo;
  // Simplified trace structure for context sidebar
  traceStructure: TraceContextSpan[];
  // Original model from trace (may differ from resolved model)
  originalModel?: string;
  originalProvider?: string;
  // Original output from the trace/span for comparison
  originalOutput?: string;
}

export interface PlaygroundPrefillData {
  messages: LLMMessage[];
  model?: string;
  provider?: string;
  // NEW: Trace context for "Trace-Aware Playground Mode"
  traceContext?: PlaygroundTraceContext;
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
 * Determine source annotation type based on message role
 */
const getSourceTypeForRole = (role: LLM_MESSAGE_ROLE): MessageSourceType => {
  switch (role) {
    case LLM_MESSAGE_ROLE.system:
      return "system_config";
    case LLM_MESSAGE_ROLE.user:
      return "user_input";
    case LLM_MESSAGE_ROLE.assistant:
    case LLM_MESSAGE_ROLE.ai:
      return "llm_response";
    case LLM_MESSAGE_ROLE.tool_execution_result:
      return "tool_output";
    default:
      return "trace_input";
  }
};

/**
 * Convert a message object to LLMMessage format with source annotation
 */
const convertToLLMMessage = (
  msg: Record<string, unknown>,
  sourceSpanName?: string,
): LLMMessage => {
  const role = getRoleFromMessage(msg);
  const mappedRole = mapRoleToLLMMessageRole(role);
  const sourceType = getSourceTypeForRole(mappedRole);

  const sourceAnnotation: MessageSourceAnnotation = {
    type: sourceType,
    sourceSpanName,
  };

  // Add description based on role
  switch (sourceType) {
    case "system_config":
      sourceAnnotation.description = "System message from trace";
      break;
    case "user_input":
      sourceAnnotation.description = sourceSpanName
        ? `User input from "${sourceSpanName}"`
        : "User input from trace";
      break;
    case "llm_response":
      sourceAnnotation.description = sourceSpanName
        ? `LLM response from "${sourceSpanName}"`
        : "LLM response from trace";
      break;
    case "tool_output":
      sourceAnnotation.description = sourceSpanName
        ? `Tool output from "${sourceSpanName}"`
        : "Tool execution result";
      break;
    default:
      sourceAnnotation.description = "From trace input";
  }

  return {
    id: generateRandomString(),
    role: mappedRole,
    content: (msg as ProviderMessageType).content,
    sourceAnnotation,
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
 * Extract output as a string for comparison
 * Handles various output formats from LLM calls
 */
const extractOutputAsString = (output: unknown): string | undefined => {
  if (!output) return undefined;

  // If output is already a string
  if (typeof output === "string") return output;

  // If output is an object
  if (isObject(output)) {
    const outputObj = output as Record<string, unknown>;

    // Check for common output structures
    // OpenAI format: { choices: [{ message: { content: "..." } }] }
    if (isArray(outputObj.choices)) {
      const firstChoice = (outputObj.choices as Array<Record<string, unknown>>)[0];
      if (firstChoice?.message && isObject(firstChoice.message)) {
        const message = firstChoice.message as Record<string, unknown>;
        if (typeof message.content === "string") {
          return message.content;
        }
      }
    }

    // Direct content field
    if (typeof outputObj.content === "string") {
      return outputObj.content;
    }

    // Message with content
    if (outputObj.message && isObject(outputObj.message)) {
      const message = outputObj.message as Record<string, unknown>;
      if (typeof message.content === "string") {
        return message.content;
      }
    }

    // Response field (common in some APIs)
    if (typeof outputObj.response === "string") {
      return outputObj.response;
    }

    // Text field
    if (typeof outputObj.text === "string") {
      return outputObj.text;
    }

    // Output field
    if (typeof outputObj.output === "string") {
      return outputObj.output;
    }

    // Fallback: stringify the entire output
    return JSON.stringify(output, null, 2);
  }

  return undefined;
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
const extractMessagesFromInput = (
  input: unknown,
  sourceSpanName?: string,
): LLMMessage[] => {
  // Case 1: Input has a messages property
  if (isObject(input) && "messages" in input) {
    const messages = (input as { messages: unknown }).messages;
    if (isMessagesArray(messages)) {
      // Handle empty messages array
      if (messages.length === 0) return [];
      return messages.map((msg) =>
        convertToLLMMessage(msg as Record<string, unknown>, sourceSpanName),
      );
    }
  }

  // Case 2: Input is directly an array of messages
  if (isMessagesArray(input)) {
    // Handle empty array
    if (input.length === 0) return [];
    return input.map((msg) =>
      convertToLLMMessage(msg as Record<string, unknown>, sourceSpanName),
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
      sourceAnnotation: {
        type: "trace_input",
        description: sourceSpanName
          ? `Input from "${sourceSpanName}"`
          : "Input from trace",
        sourceSpanName,
      },
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
 * Build trace structure from trace and spans for context sidebar
 */
const buildTraceStructure = (
  trace: Trace,
  spans: Span[],
  sourceId: string,
): TraceContextSpan[] => {
  const structure: TraceContextSpan[] = [];

  // Add trace as root
  structure.push({
    id: trace.id,
    name: trace.name,
    type: "trace",
    isSource: trace.id === sourceId,
    duration: trace.duration,
    totalTokens: trace.usage?.total_tokens,
  });

  // Add all spans
  spans.forEach((span) => {
    structure.push({
      id: span.id,
      name: span.name,
      type: span.type,
      parentSpanId: span.parent_span_id || trace.id,
      isSource: span.id === sourceId,
      model: span.model,
      provider: span.provider,
      duration: span.duration,
      totalTokens: span.usage?.total_tokens,
    });
  });

  return structure;
};

/**
 * Extract playground prefill data from a span with full trace context
 */
export const extractPlaygroundDataFromSpan = (
  span: Span,
  trace?: Trace,
  allSpans?: Span[],
): PlaygroundPrefillData => {
  const messages = extractMessagesFromInput(span.input, span.name);

  // Extract original output from the span
  const originalOutput = extractOutputAsString(span.output);

  // Build trace context if we have trace info
  let traceContext: PlaygroundTraceContext | undefined;
  if (trace) {
    traceContext = {
      traceId: trace.id,
      traceName: trace.name,
      projectId: trace.project_id,
      projectName: trace.workspace_name,
      sourceSpan: {
        id: span.id,
        name: span.name,
        type: span.type,
        model: span.model,
        provider: span.provider,
        duration: span.duration,
        totalTokens: span.usage?.total_tokens,
      },
      traceStructure: buildTraceStructure(trace, allSpans || [], span.id),
      originalModel: span.model,
      originalProvider: span.provider,
      originalOutput,
    };
  }

  return {
    messages,
    model: span.model,
    provider: span.provider,
    traceContext,
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
    return extractPlaygroundDataFromSpan(llmSpan, trace, spans);
  }

  // Fall back to trace input, but keep model/provider from LLM span if available
  const messages = extractMessagesFromInput(trace.input, trace.name);

  // Extract original output - prefer LLM span output, fall back to trace output
  const originalOutput =
    extractOutputAsString(llmSpan?.output) ||
    extractOutputAsString(trace.output);

  // Build trace context even when using trace input
  const traceContext: PlaygroundTraceContext = {
    traceId: trace.id,
    traceName: trace.name,
    projectId: trace.project_id,
    projectName: trace.workspace_name,
    // No source span when loading from trace directly
    sourceSpan: undefined,
    traceStructure: buildTraceStructure(trace, spans || [], trace.id),
    originalModel: llmSpan?.model,
    originalProvider: llmSpan?.provider,
    originalOutput,
  };

  return {
    messages,
    model: llmSpan?.model,
    provider: llmSpan?.provider,
    traceContext,
  };
};

/**
 * Extract playground prefill data from either a trace or span
 * Automatically determines the best source for messages and model info
 * Now includes full trace context for "Trace-Aware Playground Mode"
 */
export const extractPlaygroundData = (
  data: Trace | Span,
  allData?: Array<Trace | Span>,
): PlaygroundPrefillData => {
  const isSpan = "trace_id" in data && "type" in data;

  // Separate traces and spans from allData
  const spans = allData?.filter(
    (item): item is Span => "trace_id" in item && "type" in item,
  );

  if (isSpan) {
    const span = data as Span;
    // Try to find the parent trace from allData
    const trace = allData?.find(
      (item): item is Trace =>
        !("trace_id" in item) && item.id === span.trace_id,
    );
    return extractPlaygroundDataFromSpan(span, trace, spans);
  }

  return extractPlaygroundDataFromTrace(data as Trace, spans);
};

/**
 * Check if the data has extractable messages for playground
 */
export const canOpenInPlayground = (data: Trace | Span): boolean => {
  return hasNonEmptyInput(data.input);
};
