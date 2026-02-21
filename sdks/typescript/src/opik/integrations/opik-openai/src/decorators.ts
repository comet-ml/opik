import type OpenAI from "openai";

import {
  getToolCallOutput,
  parseChunk,
  parseCompletionOutput,
  parseInputArgs,
  parseUsage,
  parseModelDataFromResponse,
} from "./parsers";
import {
  GenericMethod,
  isAsyncIterable,
  ObservationData,
  OpikParent,
  TrackOpikConfig,
} from "./types";
import { Opik, OpikSpanType } from "opik";

const handleError = (
  error: Error,
  rootTracer: OpikParent,
  observationData: ObservationData
): void => {
  rootTracer.span({
    ...observationData,
    endTime: new Date(),
    type: OpikSpanType.Llm,
    errorInfo: {
      message: error.message,
      exceptionType: error.name,
      traceback: error.stack ?? "",
    },
  });
  rootTracer.end();
};

const normalizeProvider = (provider: unknown): string | undefined => {
  if (typeof provider !== "string") {
    return undefined;
  }

  const normalizedProvider = provider.trim().toLowerCase();
  return normalizedProvider.length > 0 ? normalizedProvider : undefined;
};

const isOpenRouterRoutingInferred = (model: string | undefined): boolean => {
  if (typeof model !== "string" || model.trim().length === 0) {
    return true;
  }

  const normalizedModel = model.trim();
  if (!normalizedModel.startsWith("openrouter/")) {
    return true;
  }

  const [, ...modelParts] = normalizedModel.split("/");
  if (modelParts.length === 1) {
    return ["auto", "free"].includes(modelParts[0] ?? "");
  }

  if (modelParts.length >= 2) {
    return false;
  }

  return true;
};

const buildOpenRouterMetadata = (
  model: string | undefined,
  provider: string | undefined
): Record<string, unknown> => {
  if (provider !== "openrouter") {
    return {};
  }

  return {
    created_from: "openrouter",
    type: "openrouter_chat",
    openrouter_routing_inferred: isOpenRouterRoutingInferred(model),
  };
};

export const resolveProvider = ({
  model,
  traceMetadata,
  providerHint,
}: {
  model?: string;
  traceMetadata?: Record<string, unknown>;
  providerHint?: string;
}): string => {
  const configuredProvider = normalizeProvider(traceMetadata?.provider);
  if (configuredProvider) {
    return configuredProvider;
  }

  const normalizedHintProvider = normalizeProvider(providerHint);
  if (normalizedHintProvider === "openrouter") {
    return normalizedHintProvider;
  }

  if (model && model.includes("/")) {
    const parts = model.split("/").filter(Boolean);

    if (parts.length >= 2) {
      const normalizedFirstPart = normalizeProvider(parts[0]);
      const normalizedSecondPart = normalizeProvider(parts[1]);

      if (normalizedFirstPart === "openrouter") {
        return normalizedSecondPart ?? "openrouter";
      }

      return normalizedFirstPart ?? "openai";
    }
  }

  if (providerHint) {
    return normalizeProvider(providerHint) ?? "openai";
  }

  return "openai";
};

type TracingConfig = TrackOpikConfig &
  Required<{ generationName: string; client: Opik }>;

export const withTracing = <T extends GenericMethod>(
  tracedMethod: T,
  config: TracingConfig
): ((
  ...args: Parameters<T>
) => Promise<ReturnType<T>> | ReturnType<T> | AsyncIterable<unknown>) => {
  return (...args) => wrapMethod(tracedMethod, config, ...args);
};

const wrapMethod = <T extends GenericMethod>(
  tracedMethod: T,
  config: TracingConfig,
  ...args: Parameters<T>
): Promise<ReturnType<T>> | ReturnType<T> | AsyncIterable<unknown> => {
  const { tags = [], ...configMetadata } = config?.traceMetadata ?? {};

  const { model, input, modelParameters } = parseInputArgs(
    args[0] as unknown as Record<string, unknown>
  );

  const provider = resolveProvider({
    model,
    traceMetadata: configMetadata,
    providerHint: config.provider,
  });

  const finalMetadata = {
    ...configMetadata,
    ...modelParameters,
    ...buildOpenRouterMetadata(model, provider),
    model,
  };

  const observationData = {
    model,
    input,
    provider,
    name: config.generationName,
    tags,
    startTime: new Date(),
    metadata: finalMetadata,
  };

  let rootTracer: OpikParent;

  const hasUserProvidedParent = Boolean(config?.parent);

  if (config?.parent) {
    rootTracer = config.parent;
  } else {
    rootTracer = config.client.trace(observationData);
  }

  try {
    const res = tracedMethod(...args);

    if (isAsyncIterable(res)) {
      return wrapAsyncIterable(
        res,
        rootTracer,
        hasUserProvidedParent,
        observationData,
        configMetadata,
        config.provider
      );
    }

    if (res instanceof Promise) {
      const wrappedPromise = res
        .then((result) => {
          if (isAsyncIterable(result)) {
            return wrapAsyncIterable(
              result,
              rootTracer,
              hasUserProvidedParent,
              observationData,
              configMetadata,
              config.provider
            );
          }

          const output = parseCompletionOutput(result);
          const usage = parseUsage(result);
          const { model: modelFromResponse, metadata: metadataFromResponse } =
            parseModelDataFromResponse(result);
          const resolvedProvider = resolveProvider({
            model: modelFromResponse || observationData.model,
            traceMetadata: configMetadata,
            providerHint: config.provider,
          });

          const latestMetadata = {
            ...observationData.metadata,
            ...metadataFromResponse,
            usage,
            model: modelFromResponse || observationData.model,
          };

          rootTracer.span({
            ...observationData,
            provider: resolvedProvider,
            output,
            endTime: new Date(),
            usage,
            model: modelFromResponse || observationData.model,
            type: OpikSpanType.Llm,
            metadata: latestMetadata,
          });

          if (!hasUserProvidedParent) {
            rootTracer.update({ output, endTime: new Date() });
          }

          return result;
        })
        .catch((err) => {
          handleError(err, rootTracer, observationData);
          throw err;
        });

      return wrappedPromise;
    }

    return res as ReturnType<T>;
  } catch (error) {
    handleError(error as Error, rootTracer, observationData);
    throw error;
  }
};

type ChunkUsage =
  | OpenAI.CompletionUsage
  | OpenAI.Responses.ResponseUsage
  | undefined
  | null;

const processResponseChunk = (
  rawChunk: unknown,
  observationData: ObservationData,
  traceMetadata?: Record<string, unknown>,
  providerHint?: string
): {
  output?: Record<string, unknown>;
  usage: ChunkUsage;
  chunkData: {
    isToolCall: boolean;
    data:
      | string
      | OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall;
  };
  updatedObservationData: ObservationData;
} => {
  let output;
  let usage: ChunkUsage = undefined;
  const updatedObservationData = { ...observationData };

  if (typeof rawChunk === "object" && rawChunk && "response" in rawChunk) {
    const result = rawChunk["response"];
    output = parseCompletionOutput(result);

    const {
      model: modelFromResponse,
      modelParameters: modelParametersFromResponse,
      metadata: metadataFromResponse,
    } = parseModelDataFromResponse(result);

    updatedObservationData.model = modelFromResponse ?? observationData.model;
    updatedObservationData.provider = resolveProvider({
      model: updatedObservationData.model,
      traceMetadata,
      providerHint,
    });
    updatedObservationData.metadata = {
      ...observationData.metadata,
      ...modelParametersFromResponse,
      ...metadataFromResponse,
    };

    if (typeof result === "object" && result && "usage" in result) {
      usage = result.usage as ChunkUsage;
    }
  }

  if (typeof rawChunk === "object" && rawChunk != null && "usage" in rawChunk) {
    usage = rawChunk.usage as OpenAI.CompletionUsage;
  }

  const chunkData = parseChunk(rawChunk);

  return {
    output,
    usage,
    chunkData,
    updatedObservationData,
  };
};

function wrapAsyncIterable<T>(
  iterable: AsyncIterable<unknown>,
  rootTracer: OpikParent,
  hasUserProvidedParent: boolean | undefined,
  initialObservationData: ObservationData,
  traceMetadata?: Record<string, unknown>,
  providerHint?: string
): AsyncIterable<T> {
  async function* tracedOutputGenerator(): AsyncGenerator<
    unknown,
    void,
    unknown
  > {
    let textChunks: string[] = [];
    let toolCallChunks: OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall[] =
      [];
    let completionStartTime: Date | null = null;
    let usage: ChunkUsage = undefined;
    let outputFromChunks = null;
    let observationData = { ...initialObservationData };

    for await (const rawChunk of iterable) {
      completionStartTime = completionStartTime ?? new Date();

      const {
        output,
        usage: chunkUsage,
        chunkData,
        updatedObservationData,
      } = processResponseChunk(
        rawChunk,
        observationData,
        traceMetadata,
        providerHint
      );

      if (output) outputFromChunks = output;
      if (chunkUsage) usage = chunkUsage;
      observationData = updatedObservationData;

      if (chunkData.isToolCall) {
        toolCallChunks = [
          ...toolCallChunks,
          chunkData.data as OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall,
        ];
      } else {
        textChunks = [...textChunks, chunkData.data as string];
      }

      yield rawChunk;
    }

    const finalOutput =
      outputFromChunks ??
      (toolCallChunks.length > 0
        ? getToolCallOutput(toolCallChunks)
        : { message: textChunks.join("") });

    const usageData = parseUsage({ usage });

    rootTracer.span({
      ...observationData,
      output: finalOutput,
      endTime: new Date(),
      type: OpikSpanType.Llm,
      usage: usageData,
    });

    if (!hasUserProvidedParent) {
      rootTracer.update({ output: finalOutput, endTime: new Date() });
    }
  }

  return tracedOutputGenerator() as AsyncIterable<T>;
}
