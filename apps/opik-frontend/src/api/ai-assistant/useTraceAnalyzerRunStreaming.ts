import { useCallback } from "react";
import {
  RunStreamingArgs,
  RunStreamingReturn,
} from "@/api/playground/useCompletionProxyStreaming";
import { BASE_OPIK_AI_URL, TRACE_ANALYZER_REST_ENDPOINT } from "@/api/api";

type UseTraceAnalyzerRunStreamingParams = {
  traceId: string;
  workspaceName: string;
};

export default function useTraceAnalyzerRunStreaming({
  traceId,
  workspaceName,
}: UseTraceAnalyzerRunStreamingParams) {
  return useCallback(
    async ({
      messages,
      signal,
      onAddChunk,
    }: RunStreamingArgs): Promise<RunStreamingReturn> => {
      let accumulatedValue = "";

      try {
        const isRecord = (val: unknown): val is Record<string, unknown> => {
          return val !== null && typeof val === "object";
        };

        const extractTextFromStreamPayload = (payload: unknown): string => {
          if (!isRecord(payload)) return "";

          const content = (payload as { content?: unknown }).content;

          if (typeof content === "string") return content;

          if (isRecord(content)) {
            const parts = (content as { parts?: unknown }).parts;
            if (Array.isArray(parts)) {
              return (parts as { text?: unknown }[])
                .map((part) => (typeof part.text === "string" ? part.text : ""))
                .join("");
            }
          }

          return "";
        };

        const lastUserMessage = ([...messages]
          .reverse()
          .find((m) => m.role === "user")?.content || "") as string;

        const response = await fetch(
          `${BASE_OPIK_AI_URL}${TRACE_ANALYZER_REST_ENDPOINT}${traceId}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "Comet-Workspace": workspaceName,
            },
            body: JSON.stringify({ message: lastUserMessage, streaming: true }),
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
              const delta = extractTextFromStreamPayload(parsed);
              if (delta) {
                accumulatedValue += delta;
                onAddChunk(accumulatedValue);
              }
            } catch {
              // ignore non-JSON lines
            }
          }
        }

        return {
          result: null,
          startTime: "",
          endTime: "",
          usage: null,
          choices: null,
          providerError: null,
          opikError: null,
          pythonProxyError: null,
        };
      } catch (error) {
        const typedError = error as Error;
        const isStopped = typedError.name === "AbortError";
        return {
          result: null,
          startTime: "",
          endTime: "",
          usage: null,
          choices: null,
          providerError: null,
          opikError: isStopped ? null : typedError.message,
          pythonProxyError: null,
        };
      }
    },
    [traceId, workspaceName],
  );
}
