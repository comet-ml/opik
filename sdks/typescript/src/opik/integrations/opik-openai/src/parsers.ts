import type OpenAI from "openai";
import { logger } from "opik";

type ParsedOpenAIArguments = {
  model: string;
  input: Record<string, unknown>;
  modelParameters: Record<string, unknown>;
};

export const parseInputArgs = (
  args: Record<string, unknown>
): ParsedOpenAIArguments => {
  let params: Record<string, unknown> = {};
  params = {
    frequency_penalty: args.frequency_penalty,
    logit_bias: args.logit_bias,
    logprobs: args.logprobs,
    max_tokens: args.max_tokens,
    n: args.n,
    presence_penalty: args.presence_penalty,
    seed: args.seed,
    stop: args.stop,
    stream: args.stream,
    temperature: args.temperature,
    top_p: args.top_p,
    user: args.user,
    response_format: args.response_format,
    top_logprobs: args.top_logprobs,
  };

  let input: Record<string, unknown> = args.input as Record<string, unknown>;

  if (
    args &&
    typeof args === "object" &&
    !Array.isArray(args) &&
    "messages" in args
  ) {
    input = {};
    input.messages = args.messages;
    if ("function_call" in args) {
      input.function_call = args.function_call;
    }
    if ("functions" in args) {
      input.functions = args.functions;
    }
    if ("tools" in args) {
      input.tools = args.tools;
    }

    if ("tool_choice" in args) {
      input.tool_choice = args.tool_choice;
    }
  } else if (!input) {
    input = {
      prompt: args.prompt as string,
    };
  }

  return {
    model: args.model as string,
    input: input,
    modelParameters: params,
  };
};

export const parseCompletionOutput = (res: unknown) => {
  if (
    res instanceof Object &&
    "output_text" in res &&
    res["output_text"] !== ""
  ) {
    return res["output_text"] as string;
  }

  if (
    typeof res === "object" &&
    res &&
    "output" in res &&
    Array.isArray(res["output"])
  ) {
    const output = res["output"];

    if (output.length > 1) {
      return output;
    }
    if (output.length === 1) {
      return output[0] as Record<string, unknown>;
    }

    return null;
  }

  if (
    !(res instanceof Object && "choices" in res && Array.isArray(res.choices))
  ) {
    return "";
  }

  return "message" in res.choices[0]
    ? res.choices[0].message
    : (res.choices[0].text ?? "");
};

export const parseUsage = (
  res: unknown
): Record<string, number> | undefined => {
  if (hasCompletionUsage(res)) {
    const { prompt_tokens, completion_tokens, total_tokens } = res.usage;

    return {
      input: prompt_tokens,
      output: completion_tokens,
      total: total_tokens,
    };
  }
};

export const parseUsageDetails = (
  completionUsage: OpenAI.CompletionUsage
): Record<string, number> | undefined => {
  if ("prompt_tokens" in completionUsage) {
    const {
      prompt_tokens,
      completion_tokens,
      total_tokens,
      completion_tokens_details,
      prompt_tokens_details,
    } = completionUsage;

    return {
      input: prompt_tokens,
      output: completion_tokens,
      total: total_tokens,
      ...Object.fromEntries(
        Object.entries(prompt_tokens_details ?? {}).map(([key, value]) => [
          `input_${key}`,
          value as number,
        ])
      ),
      ...Object.fromEntries(
        Object.entries(completion_tokens_details ?? {}).map(([key, value]) => [
          `output_${key}`,
          value as number,
        ])
      ),
    };
  } else if ("input_tokens" in completionUsage) {
    const {
      input_tokens,
      output_tokens,
      total_tokens,
      input_tokens_details,
      output_tokens_details,
    } = completionUsage;

    return {
      input: input_tokens,
      output: output_tokens,
      total: total_tokens,
      ...Object.fromEntries(
        Object.entries(input_tokens_details ?? {}).map(([key, value]) => [
          `input_${key}`,
          value as number,
        ])
      ),
      ...Object.fromEntries(
        Object.entries(output_tokens_details ?? {}).map(([key, value]) => [
          `output_${key}`,
          value as number,
        ])
      ),
    };
  }
};

export const parseUsageDetailsFromResponse = (
  res: unknown
): Record<string, number> | undefined => {
  if (hasCompletionUsage(res)) {
    return parseUsageDetails(res.usage);
  }
};

export const parseChunk = (
  rawChunk: unknown
):
  | { isToolCall: false; data: string }
  | {
      isToolCall: true;
      data: OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall;
    } => {
  let isToolCall = false;
  const _chunk = rawChunk as
    | OpenAI.ChatCompletionChunk
    | OpenAI.Completions.Completion;
  const chunkData = _chunk?.choices?.[0];

  try {
    if (
      "delta" in chunkData &&
      "tool_calls" in chunkData.delta &&
      Array.isArray(chunkData.delta.tool_calls)
    ) {
      isToolCall = true;

      return { isToolCall, data: chunkData.delta.tool_calls[0] };
    }
    if ("delta" in chunkData) {
      return { isToolCall, data: chunkData.delta?.content || "" };
    }

    if ("text" in chunkData) {
      return { isToolCall, data: chunkData.text || "" };
    }
  } catch (e) {
    logger.debug(`Error parsing chunk: ${e}`);
  }

  return { isToolCall: false, data: "" };
};

type CompletionUsageObject = { usage: OpenAI.CompletionUsage };

function hasCompletionUsage(obj: unknown): obj is CompletionUsageObject {
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

  const isCompletionFormat =
    typeof usage.prompt_tokens === "number" &&
    typeof usage.completion_tokens === "number" &&
    typeof usage.total_tokens === "number";

  return isCompletionFormat;
}

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
  let name = "";
  let toolArguments = "";

  for (const toolCall of toolCallChunks) {
    name = toolCall.function?.name || name;
    toolArguments += toolCall.function?.arguments || "";
  }

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
): {
  model: string | undefined;
  modelParameters: Record<string, string | number> | undefined;
  metadata: Record<string, unknown> | undefined;
} => {
  if (typeof res !== "object" || res === null) {
    return {
      model: undefined,
      modelParameters: undefined,
      metadata: undefined,
    };
  }

  const model = "model" in res ? (res["model"] as string) : undefined;
  const modelParameters: Record<string, string | number> = {};
  const modelParamKeys = [
    "max_output_tokens",
    "parallel_tool_calls",
    "store",
    "temperature",
    "tool_choice",
    "top_p",
    "truncation",
    "user",
  ];

  const metadata: Record<string, unknown> = {};
  const metadataKeys = [
    "reasoning",
    "incomplete_details",
    "instructions",
    "previous_response_id",
    "tools",
    "metadata",
    "status",
    "error",
  ];

  for (const key of modelParamKeys) {
    const val =
      key in res ? (res[key as keyof typeof res] as string | number) : null;
    if (val !== null && val !== undefined) {
      modelParameters[key as keyof typeof modelParameters] = val;
    }
  }

  for (const key of metadataKeys) {
    const val =
      key in res ? (res[key as keyof typeof res] as string | number) : null;
    if (val) {
      metadata[key as keyof typeof metadata] = val;
    }
  }

  return {
    model,
    modelParameters:
      Object.keys(modelParameters).length > 0 ? modelParameters : undefined,
    metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
  };
};
