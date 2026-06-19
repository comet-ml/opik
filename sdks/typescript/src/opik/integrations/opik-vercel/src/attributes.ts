import type { Attributes } from "@opentelemetry/api";
import type { SpanType } from "opik";

/**
 * The Vercel AI SDK has shipped two OpenTelemetry conventions, and we support
 * both:
 *
 * - `aiSdk` — AI SDK v4/v5/v6, which emit `ai.*` span attributes.
 * - `genAi` — AI SDK v7 (and frameworks built on it, such as Vercel eve),
 *   which emit OpenTelemetry GenAI `gen_ai.*` attributes (plus eve's own
 *   `eve.*` / `ai.settings.context.eve.*` context).
 *
 * Each convention parser below reads only its own keys. The exported `getSpan*`
 * helpers merge both, so a span is captured regardless of which version
 * produced it, and a key from one convention can never shadow the other.
 */

function safeParseJson(value: unknown): unknown {
  try {
    return JSON.parse(value as string);
  } catch {
    return value;
  }
}

function parseJsonObject(input: unknown): Record<string, unknown> | undefined {
  if (typeof input !== "string") {
    return undefined;
  }

  try {
    const parsed: unknown = JSON.parse(input);

    if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    return undefined;
  }

  return undefined;
}

type ChatMessage = {
  role: string;
  content?: unknown;
  tool_calls?: Array<{
    id?: string;
    type: "function";
    function: { name?: string; arguments: string };
  }>;
  tool_call_id?: string;
};

function stringifyContent(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

// Normalize one AI SDK v7 GenAI message into OpenAI chat messages (the shape the
// Opik UI pretty-renders). AI SDK / eve wrap content in `parts`, where each part
// is text, a tool call, or a tool result; OpenAI expects `content`, `tool_calls`,
// and separate `tool` messages. Messages already in `{ role, content }` shape
// pass through unchanged.
function normalizeMessage(message: unknown): ChatMessage[] {
  if (message == null || typeof message !== "object") {
    return [];
  }

  const { role, parts } = message as {
    role?: string;
    parts?: unknown;
  };
  const resolvedRole = role ?? "user";

  if (!Array.isArray(parts)) {
    // Already OpenAI/AI-SDK-native (`content` string or `[{type,text}]`).
    return [message as ChatMessage];
  }

  const messages: ChatMessage[] = [];
  const textChunks: string[] = [];
  const toolCalls: NonNullable<ChatMessage["tool_calls"]> = [];

  for (const part of parts) {
    if (part == null || typeof part !== "object") {
      continue;
    }

    const typed = part as Record<string, unknown>;

    if (typed.type === "text") {
      textChunks.push(String(typed.content ?? typed.text ?? ""));
    } else if (typed.type === "tool_call") {
      toolCalls.push({
        id: typed.id as string | undefined,
        type: "function",
        function: {
          name: typed.name as string | undefined,
          arguments: stringifyContent(typed.arguments),
        },
      });
    } else if (typed.type === "tool_call_response") {
      // Tool results become their own `tool` message in OpenAI format.
      messages.push({
        role: "tool",
        tool_call_id: typed.id as string | undefined,
        content: stringifyContent(typed.response ?? typed.output),
      });
    }
  }

  if (textChunks.length > 0 || toolCalls.length > 0) {
    const normalized: ChatMessage = { role: resolvedRole };

    if (textChunks.length > 0 || toolCalls.length === 0) {
      normalized.content = textChunks.join("");
    }

    if (toolCalls.length > 0) {
      normalized.tool_calls = toolCalls;
    }

    messages.unshift(normalized);
  }

  return messages;
}

function toChatMessages(raw: unknown): ChatMessage[] {
  if (!Array.isArray(raw)) {
    return [];
  }

  return raw.flatMap(normalizeMessage);
}

// AI SDK v4/v5/v6 — `ai.*` attributes.
const aiSdk = {
  input(attributes: Attributes): Record<string, unknown> {
    let input: Record<string, unknown> = {};

    for (const key of Object.keys(attributes)) {
      if (key === "ai.prompt") {
        const parsed = parseJsonObject(attributes[key]);

        if (parsed) {
          input = { ...input, ...parsed };
        }
      } else if (key.startsWith("ai.prompt.")) {
        input[key.slice("ai.prompt.".length)] = safeParseJson(attributes[key]);
      }
    }

    if ("ai.toolCall.name" in attributes) {
      input.toolName = attributes["ai.toolCall.name"];
    }

    if ("ai.toolCall.args" in attributes) {
      input.args = safeParseJson(attributes["ai.toolCall.args"]);
    }

    return input;
  },

  output(attributes: Attributes): Record<string, unknown> {
    const output: Record<string, unknown> = {};

    if (attributes["ai.response.text"]) {
      output.text = attributes["ai.response.text"];
    }

    if (attributes["ai.response.object"]) {
      output.object = safeParseJson(attributes["ai.response.object"]);
    }

    if (attributes["ai.response.toolCalls"]) {
      output.toolCalls = safeParseJson(attributes["ai.response.toolCalls"]);
    }

    if (attributes["ai.toolCall.result"]) {
      output.result = safeParseJson(attributes["ai.toolCall.result"]);
    }

    return output;
  },

  usage(attributes: Attributes): Record<string, number> {
    const usage: Record<string, number> = {};

    if ("ai.usage.promptTokens" in attributes) {
      usage.prompt_tokens = Number(attributes["ai.usage.promptTokens"]);
    }

    if ("ai.usage.completionTokens" in attributes) {
      usage.completion_tokens = Number(attributes["ai.usage.completionTokens"]);
    }

    return usage;
  },
};

// AI SDK v7 / OpenTelemetry GenAI — `gen_ai.*` attributes.
const genAi = {
  input(attributes: Attributes): Record<string, unknown> {
    let input: Record<string, unknown> = {};

    for (const key of Object.keys(attributes)) {
      if (key === "gen_ai.request") {
        const parsed = parseJsonObject(attributes[key]);

        if (parsed) {
          input = { ...input, ...parsed };
        }
      } else if (key.startsWith("gen_ai.request.")) {
        input[key.slice("gen_ai.request.".length)] = safeParseJson(
          attributes[key]
        );
      }
    }

    // The prompt arrives as a serialized message array; the system prompt is a
    // separate attribute. Emit OpenAI-style `messages` so the UI pretty-renders
    // the conversation, with the system prompt as the leading message.
    if ("gen_ai.input.messages" in attributes) {
      const systemMessages: ChatMessage[] = [];

      if ("gen_ai.system_instructions" in attributes) {
        const system = safeParseJson(attributes["gen_ai.system_instructions"]);
        const content = Array.isArray(system)
          ? system
              .map((part) =>
                part && typeof part === "object"
                  ? String((part as Record<string, unknown>).content ?? (part as Record<string, unknown>).text ?? "")
                  : String(part)
              )
              .join("")
          : stringifyContent(system);

        systemMessages.push({ role: "system", content });
      }

      input.messages = [
        ...systemMessages,
        ...toChatMessages(safeParseJson(attributes["gen_ai.input.messages"])),
      ];
    }

    if ("gen_ai.tool.name" in attributes) {
      input.toolName = attributes["gen_ai.tool.name"];
    }

    if ("gen_ai.tool.call.arguments" in attributes) {
      input.args = safeParseJson(attributes["gen_ai.tool.call.arguments"]);
    }

    return input;
  },

  output(attributes: Attributes): Record<string, unknown> {
    const output: Record<string, unknown> = {};

    // Emit OpenAI-style `choices` so the UI pretty-renders the model response.
    if ("gen_ai.output.messages" in attributes) {
      const raw = safeParseJson(attributes["gen_ai.output.messages"]);
      const rawList = Array.isArray(raw) ? raw : [];

      output.choices = toChatMessages(raw).map((message, index) => {
        const source = rawList[index];
        const finishReason =
          source && typeof source === "object"
            ? ((source as Record<string, unknown>).finish_reason ?? null)
            : null;

        return { index, message, finish_reason: finishReason };
      });
    }

    if ("gen_ai.tool.call.result" in attributes) {
      output.result = safeParseJson(attributes["gen_ai.tool.call.result"]);
    }

    return output;
  },

  usage(attributes: Attributes): Record<string, number> {
    const usage: Record<string, number> = {};

    if ("gen_ai.usage.input_tokens" in attributes) {
      usage.prompt_tokens = Number(attributes["gen_ai.usage.input_tokens"]);
    }

    if ("gen_ai.usage.output_tokens" in attributes) {
      usage.completion_tokens = Number(attributes["gen_ai.usage.output_tokens"]);
    }

    if ("gen_ai.usage.cache_read.input_tokens" in attributes) {
      usage["original_usage.cache_read_input_tokens"] = Number(
        attributes["gen_ai.usage.cache_read.input_tokens"]
      );
    }

    if ("gen_ai.usage.cache_creation.input_tokens" in attributes) {
      usage["original_usage.cache_creation_input_tokens"] = Number(
        attributes["gen_ai.usage.cache_creation.input_tokens"]
      );
    }

    return usage;
  },

  metadata(attributes: Attributes): Record<string, unknown> {
    const metadata: Record<string, unknown> = {};

    const model =
      attributes["gen_ai.response.model"] ?? attributes["gen_ai.request.model"];
    if (model) {
      metadata.model = model;
    }

    // `gen_ai.system` (v6) and `gen_ai.provider.name` (v7) both name the provider.
    const provider =
      attributes["gen_ai.system"] ?? attributes["gen_ai.provider.name"];
    if (provider) {
      metadata.provider = provider;
    }

    if ("gen_ai.operation.name" in attributes) {
      metadata.operation = attributes["gen_ai.operation.name"];
    }

    if ("gen_ai.response.finish_reasons" in attributes) {
      metadata.finish_reasons = safeParseJson(
        attributes["gen_ai.response.finish_reasons"]
      );
    }

    // eve framework context, exposed as `eve.*` on the turn root span and
    // `ai.settings.context.eve.*` on model-call spans.
    for (const key of Object.keys(attributes)) {
      if (key.startsWith("eve.")) {
        metadata[key] = attributes[key];
      } else if (key.startsWith("ai.settings.context.eve.")) {
        metadata[key.slice("ai.settings.context.".length)] = attributes[key];
      }
    }

    return metadata;
  },
};

export function getSpanInput(attributes: Attributes): Record<string, unknown> {
  return { ...aiSdk.input(attributes), ...genAi.input(attributes) };
}

export function getSpanOutput(attributes: Attributes): Record<string, unknown> {
  return { ...aiSdk.output(attributes), ...genAi.output(attributes) };
}

export function getSpanMetadata(
  attributes: Attributes
): Record<string, unknown> {
  return genAi.metadata(attributes);
}

export function getSpanUsage(attributes: Attributes): Record<string, number> {
  const usage = { ...aiSdk.usage(attributes), ...genAi.usage(attributes) };

  if ("prompt_tokens" in usage || "completion_tokens" in usage) {
    usage.total_tokens =
      (usage.prompt_tokens || 0) + (usage.completion_tokens || 0);
  }

  return usage;
}

// Operations that represent an actual model call (OpenTelemetry GenAI).
const LLM_OPERATIONS = new Set([
  "chat",
  "text_completion",
  "generate_content",
  "embeddings",
]);

export function getSpanType(attributes: Attributes): SpanType {
  const operation = attributes["gen_ai.operation.name"];

  if (
    operation === "execute_tool" ||
    "ai.toolCall.name" in attributes ||
    "gen_ai.tool.name" in attributes
  ) {
    return "tool";
  }

  if (typeof operation === "string") {
    // Model calls are "llm"; agent/step/other orchestration ops are "general".
    return LLM_OPERATIONS.has(operation) ? "llm" : "general";
  }

  // No GenAI operation (AI SDK v4/v5/v6 `ai.*` spans, or eve's turn span):
  // treat spans carrying model I/O as llm, everything else as general.
  const isModelCall =
    "ai.prompt" in attributes ||
    "ai.response.text" in attributes ||
    "ai.response.object" in attributes ||
    "ai.usage.promptTokens" in attributes ||
    "ai.usage.completionTokens" in attributes;

  return isModelCall ? "llm" : "general";
}

// The session/thread id a span belongs to, if any. eve groups multi-turn
// conversations under a session id, exposed on the turn root span
// (`eve.session.id`) and on model-call spans
// (`ai.settings.context.eve.session.id`).
export function getThreadId(attributes: Attributes): string | undefined {
  if (attributes["ai.telemetry.metadata.threadId"] != null) {
    return attributes["ai.telemetry.metadata.threadId"].toString();
  }

  if (attributes["eve.session.id"] != null) {
    return attributes["eve.session.id"].toString();
  }

  if (attributes["ai.settings.context.eve.session.id"] != null) {
    return attributes["ai.settings.context.eve.session.id"].toString();
  }

  return undefined;
}
