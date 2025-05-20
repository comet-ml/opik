import type OpenAI from "openai";
import { logger } from "opik";
import { flattenObject } from "./utils";

type ModelParameter =
  | "frequency_penalty"
  | "logit_bias"
  | "logprobs"
  | "max_tokens"
  | "n"
  | "presence_penalty"
  | "seed"
  | "stop"
  | "stream"
  | "temperature"
  | "top_p"
  | "user"
  | "response_format"
  | "top_logprobs";

type ChatCompletionParameter =
  | "messages"
  | "function_call"
  | "functions"
  | "tools"
  | "tool_choice";

interface ParsedOpenAIArguments {
  model: string | undefined;
  input: Record<string, unknown>;
  modelParameters: Record<string, unknown>;
}

interface CompletionUsageObject<T> {
  usage: T;
}

type ChunkResult =
  | { isToolCall: false; data: string }
  | {
      isToolCall: true;
      data: OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall;
    };

interface ModelMetadataResult {
  model: string | undefined;
  modelParameters: Record<string, unknown> | undefined;
  metadata: Record<string, unknown> | undefined;
}

export const parseInputArgs = (
  args: Record<string, unknown>
): ParsedOpenAIArguments => {
  return {
    model: args.model as string | undefined,
    input: parseInputFormat(args),
    modelParameters: extractModelParameters(args),
  };
};

const extractModelParameters = (
  args: Record<string, unknown>
): Record<string, unknown> => {
  const parameterNames: readonly ModelParameter[] = [
    "frequency_penalty",
    "logit_bias",
    "logprobs",
    "max_tokens",
    "n",
    "presence_penalty",
    "seed",
    "stop",
    "stream",
    "temperature",
    "top_p",
    "user",
    "response_format",
    "top_logprobs",
  ] as const;

  return parameterNames.reduce<Record<string, unknown>>(
    (params, name) =>
      args[name] !== undefined ? { ...params, [name]: args[name] } : params,
    {}
  );
};

const parseInputFormat = (
  args: Record<string, unknown>
): Record<string, unknown> => {
  if (isChatCompletionFormat(args)) {
    return extractChatCompletionParams(args);
  }

  if ("prompt" in args) {
    return { prompt: args.prompt };
  }

  if ("input" in args) {
    return parseInputField(args.input);
  }

  return {};
};

const isChatCompletionFormat = (args: Record<string, unknown>): boolean => {
  return Boolean(
    args &&
      typeof args === "object" &&
      !Array.isArray(args) &&
      "messages" in args
  );
};

const extractChatCompletionParams = (
  args: Record<string, unknown>
): Record<string, unknown> => {
  const chatParams: readonly ChatCompletionParameter[] = [
    "messages",
    "function_call",
    "functions",
    "tools",
    "tool_choice",
  ] as const;

  return chatParams.reduce<Record<string, unknown>>(
    (params, name) =>
      name in args ? { ...params, [name]: args[name] } : params,
    {}
  );
};

const parseInputField = (input: unknown): Record<string, unknown> => {
  const isValidObject =
    typeof input === "object" && input !== null && !Array.isArray(input);

  return isValidObject ? (input as Record<string, unknown>) : { input };
};

export const parseCompletionOutput = (
  res: unknown
): Record<string, unknown> | undefined => {
  if (isOutputTextFormat(res)) {
    return { content: res["output_text"] };
  }

  if (isOutputArrayFormat(res)) {
    const output = res["output"] as unknown[];
    const parsedOutput = parseOutputArray(output);

    if (parsedOutput === null) {
      return undefined;
    }

    return Array.isArray(parsedOutput)
      ? { outputs: parsedOutput }
      : (parsedOutput as Record<string, unknown>);
  }

  if (isChoicesFormat(res)) {
    const extracted = extractFromChoices(res);
    return typeof extracted === "string"
      ? { content: extracted }
      : (extracted as Record<string, unknown>);
  }

  return undefined;
};

const isOutputTextFormat = (res: unknown): res is { output_text: string } => {
  return Boolean(
    res instanceof Object &&
      "output_text" in res &&
      typeof res["output_text"] === "string" &&
      res["output_text"] !== ""
  );
};

const isOutputArrayFormat = (res: unknown): res is { output: unknown[] } => {
  return Boolean(
    typeof res === "object" &&
      res !== null &&
      "output" in res &&
      Array.isArray(res["output"])
  );
};

const parseOutputArray = (
  output: unknown[]
): unknown[] | Record<string, unknown> | null => {
  if (output.length > 1) {
    return output;
  }
  if (
    output.length === 1 &&
    typeof output[0] === "object" &&
    output[0] !== null
  ) {
    return output[0] as Record<string, unknown>;
  }
  if (output.length === 1) {
    return { content: output[0] };
  }
  return null;
};

const isChoicesFormat = (
  res: unknown
): res is { choices: Array<Record<string, unknown>> } => {
  return Boolean(
    res instanceof Object &&
      "choices" in res &&
      Array.isArray(res.choices) &&
      res.choices.length > 0
  );
};

const extractFromChoices = (res: {
  choices: Array<Record<string, unknown>>;
}): Record<string, unknown> | string => {
  const firstChoice = res.choices[0];

  if (
    "message" in firstChoice &&
    typeof firstChoice.message === "object" &&
    firstChoice.message !== null
  ) {
    return firstChoice.message as Record<string, unknown>;
  }

  return "text" in firstChoice && firstChoice.text
    ? String(firstChoice.text)
    : "";
};

export const parseUsage = (
  res: unknown
): Record<string, number> | undefined => {
  if (hasCompletionUsage(res)) {
    return {
      completion_tokens: res.usage.completion_tokens,
      prompt_tokens: res.usage.prompt_tokens,
      total_tokens: res.usage.total_tokens,
      ...flattenObject(res.usage, "original_usage"),
    };
  }

  if (hasResponseUsage(res)) {
    return {
      completion_tokens: res.usage.input_tokens,
      prompt_tokens: res.usage.output_tokens,
      total_tokens: res.usage.total_tokens,
      ...flattenObject(res.usage, "original_usage"),
    };
  }

  return undefined;
};

const hasCompletionUsage = (
  obj: unknown
): obj is CompletionUsageObject<OpenAI.CompletionUsage> => {
  if (
    !obj ||
    typeof obj !== "object" ||
    !("usage" in obj) ||
    !obj.usage ||
    typeof obj.usage !== "object"
  ) {
    return false;
  }

  const usage = obj.usage as Record<string, unknown>;

  return (
    typeof usage.prompt_tokens === "number" &&
    typeof usage.completion_tokens === "number" &&
    typeof usage.total_tokens === "number"
  );
};

const hasResponseUsage = (
  obj: unknown
): obj is CompletionUsageObject<OpenAI.Responses.ResponseUsage> => {
  if (
    !obj ||
    typeof obj !== "object" ||
    !("usage" in obj) ||
    !obj.usage ||
    typeof obj.usage !== "object"
  ) {
    return false;
  }

  const usage = obj.usage as Record<string, unknown>;

  return (
    typeof usage.total_tokens === "number" &&
    typeof usage.input_tokens === "number" &&
    typeof usage.output_tokens === "number"
  );
};

export const parseChunk = (rawChunk: unknown): ChunkResult => {
  const _chunk = rawChunk as
    | OpenAI.ChatCompletionChunk
    | OpenAI.Completions.Completion;
  const chunkData = _chunk?.choices?.[0];

  try {
    if (isToolCallDelta(chunkData)) {
      return {
        isToolCall: true,
        data: chunkData.delta.tool_calls[0],
      };
    }

    if (isDeltaChunk(chunkData)) {
      return {
        isToolCall: false,
        data: chunkData.delta?.content || "",
      };
    }

    if (isTextChunk(chunkData)) {
      return {
        isToolCall: false,
        data: chunkData.text || "",
      };
    }
  } catch (e) {
    logger.debug(`Error parsing chunk: ${e}`);
  }

  return { isToolCall: false, data: "" };
};

const isToolCallDelta = (
  chunkData: unknown
): chunkData is {
  delta: {
    tool_calls: OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall[];
  };
} => {
  return Boolean(
    chunkData &&
      typeof chunkData === "object" &&
      "delta" in chunkData &&
      chunkData.delta &&
      typeof chunkData.delta === "object" &&
      "tool_calls" in chunkData.delta &&
      Array.isArray(chunkData.delta.tool_calls) &&
      chunkData.delta.tool_calls.length > 0
  );
};

const isDeltaChunk = (
  chunkData: unknown
): chunkData is {
  delta: { content?: string };
} => {
  return Boolean(
    chunkData && typeof chunkData === "object" && "delta" in chunkData
  );
};

const isTextChunk = (
  chunkData: unknown
): chunkData is {
  text?: string;
} => {
  return Boolean(
    chunkData && typeof chunkData === "object" && "text" in chunkData
  );
};

export const getToolCallOutput = (
  toolCallChunks: OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall[]
): {
  tool_calls: {
    function: {
      name: string;
      arguments: string;
    };
  }[];
} => {
  const { name, arguments: toolArguments } = toolCallChunks.reduce(
    (result, chunk) => ({
      name: chunk.function?.name || result.name,
      arguments: result.arguments + (chunk.function?.arguments || ""),
    }),
    { name: "", arguments: "" }
  );

  return {
    tool_calls: [
      {
        function: {
          name,
          arguments: toolArguments,
        },
      },
    ],
  };
};

export const parseModelDataFromResponse = (
  res: unknown
): ModelMetadataResult => {
  if (typeof res !== "object" || res === null) {
    return {
      model: undefined,
      modelParameters: undefined,
      metadata: undefined,
    };
  }

  const model = isModelField(res) ? res.model : undefined;
  const modelParameters = extractModelParametersFromResponse(res);
  const metadata = extractMetadataFromResponse(res);

  return {
    model,
    modelParameters:
      Object.keys(modelParameters).length > 0 ? modelParameters : undefined,
    metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
  };
};

const isModelField = (res: object): res is { model: string } => {
  return "model" in res && typeof res.model === "string";
};

const extractModelParametersFromResponse = (
  res: object
): Record<string, unknown> => {
  const modelParamKeys = [
    "max_output_tokens",
    "parallel_tool_calls",
    "store",
    "temperature",
    "tool_choice",
    "top_p",
    "truncation",
    "user",
  ] as const;

  return extractFieldsFromResponse(res, modelParamKeys);
};

const extractMetadataFromResponse = (res: object): Record<string, unknown> => {
  const metadataKeys = [
    "reasoning",
    "incomplete_details",
    "instructions",
    "previous_response_id",
    "tools",
    "metadata",
    "status",
    "error",
  ] as const;

  return extractFieldsFromResponse(res, metadataKeys);
};

const extractFieldsFromResponse = <T extends string>(
  res: object,
  keys: readonly T[]
): Record<string, unknown> => {
  const result: Record<string, unknown> = {};

  for (const key of keys) {
    if (key in res && res[key as keyof typeof res] != null) {
      result[key] = res[key as keyof typeof res];
    }
  }

  return result;
};
