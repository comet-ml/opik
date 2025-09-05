import { Serialized } from "@langchain/core/load/serializable";
import { Generation, ChatGeneration } from "@langchain/core/outputs";
import { BaseMessage, ToolMessage } from "@langchain/core/messages";
import { ChainValues } from "@langchain/core/utils/types";
import {
  ContentContainer,
  KwargsContainer,
  MessageContainer,
  ValueContainer,
} from "./types";

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const hasContent = (
  obj: unknown,
): obj is ContentContainer & Record<string, unknown> =>
  isObject(obj) && "content" in obj;

const hasMessages = (
  obj: unknown,
): obj is MessageContainer & Record<string, unknown> =>
  isObject(obj) && "messages" in obj;

const hasValue = (
  obj: unknown,
): obj is ValueContainer & Record<string, unknown> =>
  isObject(obj) && "value" in obj;

const hasKwargs = (
  obj: unknown,
): obj is KwargsContainer & Record<string, unknown> =>
  isObject(obj) && "kwargs" in obj;

export const pick = (...values: unknown[]) =>
  values.find((value) => value !== undefined && value !== null);

export const cleanObject = (
  obj: Record<string, unknown>,
): Record<string, unknown> =>
  Object.fromEntries(
    Object.entries(obj).filter(([, value]) => {
      if (value === undefined || value === null) return false;
      if (Array.isArray(value) && value.length === 0) return false;
      if (isObject(value) && Object.keys(value).length === 0) {
        return false;
      }
      return true;
    }),
  );

export const safeParseSerializedJson = (value: string) => {
  try {
    return JSON.parse(value);
  } catch {
    return { value };
  }
};

export const getMessageContent = (
  message: BaseMessage,
): Record<string, unknown> => {
  let role = message.name ?? message.getType();

  if (message.getType() === "human") {
    role = "user";
  } else if (message.getType() === "ai") {
    role = "assistant";
  } else if (message.getType() === "system") {
    role = "system";
  }

  const additionalProps: Record<string, unknown> = {};

  const msg = message as unknown as Record<string, unknown>;
  if ("tool_calls" in msg) additionalProps.tool_calls = msg.tool_calls;
  if ("status" in msg) additionalProps.status = msg.status;
  if ("artifact" in msg) additionalProps.artifact = msg.artifact;

  return cleanObject({
    content: message.content,
    role,
    ...additionalProps,
  });
};

export const inputFromMessages = (input: BaseMessage[][]) => {
  const messages = input.flatMap((batch) => batch.map(getMessageContent));
  return { messages };
};

export const parseGeneration = (
  generation: Generation | ChatGeneration,
): unknown => {
  if ("message" in generation) {
    return getMessageContent(generation.message);
  }

  if ("text" in generation && generation.text) {
    return generation.text;
  }

  return generation;
};

export const outputFromGenerations = (
  input: Generation[][] | ChatGeneration[],
) => {
  const generations = input.flatMap((batch) => {
    return Array.isArray(batch)
      ? batch.map(parseGeneration)
      : parseGeneration(batch);
  });

  return { generations };
};

export const outputFromToolOutput = (output: unknown | ToolMessage) =>
  output instanceof ToolMessage ? getMessageContent(output) : undefined;

export const extractCallArgs = (
  llm: Serialized,
  invocationParams: Record<string, unknown>,
  metadata?: Record<string, unknown>,
): Record<string, unknown> => {
  const args = cleanObject({
    model: pick(invocationParams?.model, metadata?.ls_model_name, llm.name),
    temperature: pick(invocationParams?.temperature, metadata?.ls_temperature),
    top_p: pick(invocationParams?.top_p, invocationParams?.topP),
    top_k: pick(invocationParams?.top_k, invocationParams?.topK),
    max_tokens: pick(
      invocationParams?.max_tokens,
      invocationParams?.maxOutputTokens,
    ),
    frequency_penalty: invocationParams?.frequency_penalty,
    presence_penalty: invocationParams?.presence_penalty,
    response_format: invocationParams?.response_format,
    tool_choice: invocationParams?.tool_choice,
    function_call: invocationParams?.function_call,
    n: invocationParams?.n,
    stop: pick(invocationParams?.stop, invocationParams?.stop_sequence),
  });

  return !Object.keys(args).length ? invocationParams : args;
};

const parseChainValue = (output: unknown): Record<string, unknown> => {
  if (output === null || output === undefined) {
    return { value: output };
  }

  if (
    typeof output === "string" ||
    typeof output === "number" ||
    typeof output === "boolean"
  ) {
    return { value: output };
  }

  if (!isObject(output)) {
    return { value: output };
  }

  if (hasContent(output) && output.content !== undefined) {
    const content = output.content;
    return isObject(content)
      ? (content as Record<string, unknown>)
      : { value: content };
  }

  if (hasMessages(output) && output.messages !== undefined) {
    const messages = Array.isArray(output.messages)
      ? output.messages.map(parseChainValue)
      : [parseChainValue(output.messages)];
    return { messages };
  }

  if (hasValue(output) && output.value !== undefined) {
    const value = output.value;
    return isObject(value) ? (value as Record<string, unknown>) : { value };
  }

  if (hasKwargs(output) && output.kwargs !== undefined) {
    return parseChainValue(output.kwargs);
  }

  return Object.fromEntries(
    Object.entries(output).map(([key, value]) => [key, parseChainValue(value)]),
  );
};

export const outputFromChainValues = (
  output: unknown,
): Record<string, unknown> => {
  try {
    const parsed = (Array.isArray(output) ? output : [output])
      .filter(
        (item): item is NonNullable<typeof item> =>
          item !== undefined && item !== null,
      )
      .flatMap(parseChainValue);

    if (parsed.length === 1) {
      return parsed[0] as Record<string, unknown>;
    } else if (parsed.length > 1) {
      return { values: parsed };
    } else {
      return {};
    }
  } catch (error) {
    console.warn("Error processing chain outputs:", error);
    return { error: String(error) };
  }
};

export const inputFromChainValues = (
  inputs: ChainValues,
): Record<string, unknown> => {
  try {
    const parsed = (Array.isArray(inputs) ? inputs : [inputs])
      .filter(
        (item): item is NonNullable<typeof item> =>
          item !== undefined && item !== null,
      )
      .flatMap(parseChainValue);

    if (parsed.length === 1) {
      return parsed[0] as Record<string, unknown>;
    } else if (parsed.length > 1) {
      return { values: parsed };
    } else {
      return {};
    }
  } catch (error) {
    console.warn("Error processing chain inputs:", error);
    return { error: String(error) };
  }
};
