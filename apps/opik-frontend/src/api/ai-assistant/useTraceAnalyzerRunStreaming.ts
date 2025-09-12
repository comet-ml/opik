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
  role: string | "progress";
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

const isRecord = (val: unknown): val is Record<string, unknown> =>
  isPlainObject(val);

const isValidStreamResponse = (payload: unknown): payload is StreamResponse => {
  if (!isRecord(payload)) return false;

  const content = get(payload, "content");
  if (!isPlainObject(content)) return false;

  const parts = get(content, "parts");
  return isArray(parts);
};

const isErrorResponse = (payload: unknown): payload is StreamResponse => {
  if (!isRecord(payload)) return false;
  const error = get(payload, "error");
  return isString(error) && error.trim() !== "";
};

const extractDataFromStreamPayload = (
  payload: StreamResponse,
): { text: string; role: string } => {
  const content = payload.content;
  const text = compact(
    content.parts.map((part) => (isString(part.text) ? part.text : "")),
  ).join("");

  return {
    text,
    role: content.role,
  };
};

const extractErrorFromStreamPayload = (payload: StreamResponse): string =>
  get(payload, "error", "");

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

              if (isErrorResponse(parsed)) {
                detectedError = extractErrorFromStreamPayload(parsed);
                break;
              }

              if (isValidStreamResponse(parsed)) {
                const data = extractDataFromStreamPayload(parsed);

                if (data.role === "progress") {
                  onAddChunk({
                    content: "",
                    status: data.text,
                  });
                } else {
                  accumulatedValue = parsed.partial
                    ? accumulatedValue + data.text
                    : data.text;
                  onAddChunk({
                    content: accumulatedValue,
                    status: "",
                  });
                }
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
