import { useCallback } from "react";
import { isPlainObject, isString, isArray, compact } from "lodash";
import type {
  TraceAnalyzerRunStreamingArgs,
  TraceAnalyzerRunStreamingReturn,
  MESSAGE_TYPE,
  ToolCall,
  ToolResponse,
} from "@/types/ai-assistant";
import api, { BASE_OPIK_AI_URL, TRACE_ANALYZER_REST_ENDPOINT } from "@/api/api";

type StreamResponsePart = {
  text: string;
};

type StreamResponseContent = {
  parts: StreamResponsePart[];
};

type StreamResponse = {
  message_type: MESSAGE_TYPE;
  // For response events
  content?: StreamResponseContent;
  partial?: boolean;
  invocationId?: string;
  // For tool events
  tool_call?: ToolCall;
  tool_response?: ToolResponse;
  // Common
  id: string;
  error?: string;
};

const isRecord = (val: unknown): val is Record<string, unknown> =>
  isPlainObject(val);

const isValidStreamResponse = (payload: unknown): payload is StreamResponse => {
  if (!isRecord(payload)) return false;

  // Must have message_type
  const messageType = payload.message_type;
  if (!isString(messageType)) return false;

  // Validate based on message type
  switch (messageType) {
    case "response": {
      const content = payload.content;
      return (
        isPlainObject(content) &&
        isArray((content as Record<string, unknown>).parts)
      );
    }
    case "tool_call":
      return isPlainObject(payload.tool_call);
    case "tool_complete":
      return isPlainObject(payload.tool_response);
    default:
      return false;
  }
};

const isErrorResponse = (payload: unknown): payload is StreamResponse => {
  if (!isRecord(payload)) return false;
  const error = payload.error;
  return isString(error) && error.trim() !== "";
};

const extractTextFromStreamPayload = (payload: StreamResponse): string => {
  // content is guaranteed to exist for "response" events by isValidStreamResponse
  const content = payload.content!;
  return compact(
    content.parts.map((part) => (isString(part.text) ? part.text : "")),
  ).join("");
};

const extractErrorFromStreamPayload = (payload: StreamResponse): string =>
  payload.error ?? "";

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
                const messageType = parsed.message_type;

                // Handle different message types
                switch (messageType) {
                  case "response": {
                    const text = extractTextFromStreamPayload(parsed);
                    // Use invocationId to group streaming chunks together, fall back to id
                    const streamId = parsed.invocationId || parsed.id;
                    onAddChunk({
                      messageType,
                      eventId: streamId,
                      content: text,
                      partial: parsed.partial,
                    });
                    break;
                  }
                  case "tool_call":
                    if (parsed.tool_call) {
                      onAddChunk({
                        messageType: "tool_call" as MESSAGE_TYPE,
                        eventId: parsed.id,
                        toolCall: parsed.tool_call,
                      });
                    }
                    break;
                  case "tool_complete":
                    if (parsed.tool_response) {
                      onAddChunk({
                        messageType: "tool_complete" as MESSAGE_TYPE,
                        eventId: parsed.id,
                        toolResponse: parsed.tool_response,
                      });
                    }
                    break;
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
