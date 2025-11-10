import { OpikSpanType } from "opik";
import type {
  GenericMethod,
  ObservationData,
  OpikParent,
  TracingConfig,
  GeminiFunctionCall,
  GeminiResponse,
} from "./types";
import {
  parseChunk,
  parseCompletionOutput,
  parseInputArgs,
  parseModelDataFromResponse,
  parseUsage,
} from "./parsers";
import { isAsyncIterable } from "./utils";

/**
 * Main tracing wrapper for Gemini SDK methods
 * Handles sync, Promise, and AsyncIterable responses
 */
export const withTracing = <T extends GenericMethod>(
  tracedMethod: T,
  config: TracingConfig
): ((...args: Parameters<T>) => ReturnType<T>) => {
  return (...args: Parameters<T>): ReturnType<T> => {
    // 1. Parse input arguments
    const { model, input, modelParameters } = parseInputArgs(
      args[0] as Record<string, unknown>
    );

    // 2. Build initial observation data (use dynamic provider from config)
    const { tags = [], ...configMetadata } = config?.traceMetadata ?? {};
    const observationData: ObservationData = {
      name: config.generationName,
      startTime: new Date(),
      input,
      model,
      provider: config.provider,
      metadata: {
        ...configMetadata,
        ...modelParameters,
      },
      tags: ["genai", ...tags],
    };

    // 3. Create trace or span
    let rootTracer: OpikParent;
    const hasUserProvidedParent = Boolean(config?.parent);

    if (config?.parent) {
      rootTracer = config.parent;
    } else {
      rootTracer = config.client.trace(observationData);
    }

    try {
      // 4. Execute original method
      const res = tracedMethod(...args);

      // 5. Handle streaming (async iterable)
      if (isAsyncIterable(res)) {
        return wrapAsyncIterable(
          res,
          rootTracer,
          hasUserProvidedParent,
          observationData
        ) as ReturnType<T>;
      }

      // 6. Handle Promise responses
      if (res instanceof Promise) {
        const wrappedPromise = res
          .then((result) => {
            // Check if Promise resolves to async iterable
            if (isAsyncIterable(result)) {
              return wrapAsyncIterable(
                result,
                rootTracer,
                hasUserProvidedParent,
                observationData
              );
            }

            // Handle sync response
            const output = parseCompletionOutput(result);
            const usage = parseUsage(result);
            const { model: modelFromResponse, metadata: metadataFromResponse } =
              parseModelDataFromResponse(result);

            // Create main span with complete data
            const mainSpan = rootTracer.span({
              ...observationData,
              output,
              endTime: new Date(),
              usage,
              model: modelFromResponse || observationData.model,
              type: OpikSpanType.Llm,
              metadata: {
                ...observationData.metadata,
                ...metadataFromResponse,
                usage,
              },
            });

            // Check for function calls and create child spans
            const geminiResult = result as GeminiResponse;
            if (
              geminiResult.functionCalls &&
              Array.isArray(geminiResult.functionCalls)
            ) {
              geminiResult.functionCalls.forEach(
                (functionCall: GeminiFunctionCall) => {
                  mainSpan.span({
                    name: `function_call: ${functionCall.name}`,
                    startTime: new Date(),
                    endTime: new Date(),
                    input: { arguments: functionCall.args },
                    output: { function_name: functionCall.name },
                    type: OpikSpanType.Tool,
                    metadata: {
                      function_name: functionCall.name,
                      function_arguments: functionCall.args,
                    },
                  });
                }
              );
            }

            // Update trace output (will be overwritten by next call if multiple calls)
            if (!hasUserProvidedParent) {
              rootTracer.update({ output, endTime: new Date() });
            }

            return result;
          })
          .catch((err) => {
            handleError(err, rootTracer, observationData);
            throw err;
          });

        return wrappedPromise as ReturnType<T>;
      }

      // 7. Handle sync response (unlikely for Gemini)
      const output = parseCompletionOutput(res);
      const usage = parseUsage(res);
      const { model: modelFromResponse, metadata: metadataFromResponse } =
        parseModelDataFromResponse(res);

      // Create main span with complete data
      const mainSpan = rootTracer.span({
        ...observationData,
        output,
        endTime: new Date(),
        usage,
        model: modelFromResponse || observationData.model,
        type: OpikSpanType.Llm,
        metadata: {
          ...observationData.metadata,
          ...metadataFromResponse,
          usage,
        },
      });

      // Check for function calls and create child spans
      const geminiRes = res as GeminiResponse;
      if (geminiRes.functionCalls && Array.isArray(geminiRes.functionCalls)) {
        geminiRes.functionCalls.forEach((functionCall: GeminiFunctionCall) => {
          mainSpan.span({
            name: `function_call: ${functionCall.name}`,
            startTime: new Date(),
            endTime: new Date(),
            input: { arguments: functionCall.args },
            output: { function_name: functionCall.name },
            type: OpikSpanType.Tool,
            metadata: {
              function_name: functionCall.name,
              function_arguments: functionCall.args,
            },
          });
        });
      }

      // Update trace output (will be overwritten by next call if multiple calls)
      if (!hasUserProvidedParent) {
        rootTracer.update({ output, endTime: new Date() });
      }

      return res as ReturnType<T>;
    } catch (error) {
      handleError(error as Error, rootTracer, observationData);
      throw error;
    }
  };
};

/**
 * Wrap an async iterable to capture streaming responses
 * Aggregates chunks and creates span with complete data when stream ends
 */
function wrapAsyncIterable<T>(
  iterable: AsyncIterable<unknown>,
  rootTracer: OpikParent,
  hasUserProvidedParent: boolean,
  initialObservationData: ObservationData
): AsyncIterable<T> {
  async function* tracedOutputGenerator(): AsyncGenerator<
    unknown,
    void,
    unknown
  > {
    const chunks: unknown[] = [];
    const textChunks: string[] = [];
    let usage: Record<string, number> | undefined;
    let aggregatedOutput: Record<string, unknown> = {};
    let modelMetadata:
      | {
          model: string | undefined;
          metadata: Record<string, unknown> | undefined;
        }
      | undefined;

    try {
      // Iterate through stream
      for await (const rawChunk of iterable) {
        chunks.push(rawChunk);

        // Parse chunk
        const chunkData = parseChunk(rawChunk);

        if (!chunkData.isToolCall) {
          textChunks.push(chunkData.data);
        }

        // Yield chunk to caller (pass-through)
        yield rawChunk;
      }

      // After stream completes:
      // Parse usage and metadata from final chunk (Gemini includes usage_metadata in final response)
      const finalChunk = chunks[chunks.length - 1];

      if (finalChunk) {
        usage = parseUsage(finalChunk);
        modelMetadata = parseModelDataFromResponse(finalChunk);

        const accumulatedText = textChunks.join("");
        const finalChunkParsed = parseCompletionOutput(finalChunk);

        if (finalChunkParsed?.candidates) {
          aggregatedOutput = {
            candidates: structuredClone(finalChunkParsed.candidates),
          };

          // Type assertion for nested property access after structuredClone
          const typedOutput = aggregatedOutput as {
            candidates?: Array<{
              content?: { parts?: Array<{ text?: string }> };
            }>;
          };
          if (typedOutput.candidates?.[0]?.content?.parts?.[0]) {
            typedOutput.candidates[0].content.parts[0].text = accumulatedText;
          }
        }
      }

      // 2. Create main span with aggregated data
      const mainSpan = rootTracer.span({
        ...initialObservationData,
        output: aggregatedOutput,
        endTime: new Date(),
        usage,
        model: modelMetadata?.model || initialObservationData.model,
        type: OpikSpanType.Llm,
        metadata: {
          ...initialObservationData.metadata,
          ...modelMetadata?.metadata,
          usage,
        },
      });

      // 3. Check for function calls in final chunk and create child spans
      if (finalChunk && typeof finalChunk === "object") {
        const geminiChunk = finalChunk as GeminiResponse;
        if (
          geminiChunk.functionCalls &&
          Array.isArray(geminiChunk.functionCalls)
        ) {
          geminiChunk.functionCalls.forEach(
            (functionCall: GeminiFunctionCall) => {
              mainSpan.span({
                name: `function_call: ${functionCall.name}`,
                startTime: new Date(),
                endTime: new Date(),
                input: { arguments: functionCall.args },
                output: { function_name: functionCall.name },
                type: OpikSpanType.Tool,
                metadata: {
                  function_name: functionCall.name,
                  function_arguments: functionCall.args,
                },
              });
            }
          );
        }
      }

      // 4. Update trace output (will be overwritten by next call if multiple calls)
      if (!hasUserProvidedParent) {
        rootTracer.update({ output: aggregatedOutput, endTime: new Date() });
      }
    } catch (error) {
      handleError(error as Error, rootTracer, initialObservationData);
      throw error;
    }
  }

  return tracedOutputGenerator() as AsyncIterable<T>;
}

/**
 * Handle errors by creating an error span and ending the trace
 */
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
