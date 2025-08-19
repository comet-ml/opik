import { useCallback } from "react";
import { isPlainObject, get, isString, isArray, compact } from "lodash";
import type {
  TraceAnalyzerRunStreamingArgs,
  TraceAnalyzerRunStreamingReturn,
} from "@/types/ai-assistant";
import api, { BASE_OPIK_AI_URL, TRACE_ANALYZER_REST_ENDPOINT } from "@/api/api";

type StreamResponsePart = {
  text: string;
};

type StreamResponseContent = {
  parts: StreamResponsePart[];
  role: string;
};

type StreamResponse = {
  content: StreamResponseContent;
  partial: boolean;
  invocationId: string;
  author: string;
  actions: {
    stateDelta: Record<string, unknown>;
    artifactDelta: Record<string, unknown>;
    requestedAuthConfigs: Record<string, unknown>;
  };
  id: string;
  timestamp: number;
  error?: string;
};

const isRecord = (val: unknown): val is Record<string, unknown> => {
  return isPlainObject(val);
};

const isValidStreamResponse = (payload: unknown): payload is StreamResponse => {
  if (!isRecord(payload)) return false;

  const content = get(payload, "content");
  if (!isPlainObject(content)) return false;

  const parts = get(content, "parts");
  return isArray(parts);
};

const extractTextFromStreamPayload = (payload: unknown): string => {
  // Validate payload conforms to standard stream response format
  if (!isValidStreamResponse(payload)) return "";

  // Extract text from content.parts array
  const parts = payload.content.parts;
  const textParts = compact(
    parts.map((part) => (isString(part.text) ? part.text : "")),
  );

  return textParts.join("");
};

type UseTraceAnalyzerRunStreamingParams = {
  traceId: string;
};

export default function useTraceAnalyzerRunStreaming({
  traceId,
}: UseTraceAnalyzerRunStreamingParams) {
  return useCallback(
    async ({
      message,
      signal,
      onAddChunk,
    }: TraceAnalyzerRunStreamingArgs): Promise<TraceAnalyzerRunStreamingReturn> => {
      let accumulatedValue = "";
      let detectedError: string | null = null;

      try {
        const response = await fetch(
          `${BASE_OPIK_AI_URL}${TRACE_ANALYZER_REST_ENDPOINT}${traceId}`,
          {
            method: "POST",
            headers: (() => {
              const headers: Record<string, string> = {
                "Content-Type": "application/json",
              };
              const workspace = api.defaults.headers.common["Comet-Workspace"];
              if (typeof workspace === "string") {
                headers["Comet-Workspace"] = workspace;
              }
              return headers;
            })(),
            body: JSON.stringify({ message, streaming: true }),
            credentials: "include",
            signal,
          },
        );

        if (!response.ok || !response.body) {
          throw new Error("Failed to start streaming response");
        }

        const reader = response?.body?.getReader();
        const decoder = new TextDecoder("utf-8");

        while (reader) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split("\n").filter((line) => line.trim() !== "");
          for (const line of lines) {
            const jsonData = line.startsWith("data:")
              ? line.split("data:")[1]
              : line;
            try {
              const parsed = JSON.parse(jsonData) as unknown;
              // Detect error payloads in standard stream response format
              if (isRecord(parsed)) {
                const maybeError = get(parsed, "error");
                if (isString(maybeError) && maybeError.trim() !== "") {
                  detectedError = maybeError;
                  break;
                }
              }

              const delta = extractTextFromStreamPayload(parsed);
              if (delta) {
                // Check if this is a valid stream response to access the partial field
                if (isValidStreamResponse(parsed)) {
                  if (parsed.partial) {
                    // Accumulate when partial is true
                    accumulatedValue += delta;
                  } else {
                    // Replace when partial is false
                    accumulatedValue = delta;
                  }
                } else {
                  // Fallback: accumulate if we can't determine partial status
                  accumulatedValue += delta;
                }
                onAddChunk(accumulatedValue);
              }
            } catch {
              // ignore non-JSON lines
            }
          }
          if (detectedError) break;
        }

        return {
          error: detectedError,
        };
      } catch (error) {
        const typedError = error as Error;
        const isStopped = typedError.name === "AbortError";
        return {
          error: isStopped ? null : typedError.message,
        };
      }
    },
    [traceId],
  );
}
