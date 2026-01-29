import { useCallback } from "react";
import { isPlainObject, get, isString } from "lodash";
import type {
  OptimizerRunStreamingArgs,
  OptimizerRunStreamingReturn,
  AgentOptimizerMessage,
} from "@/types/agent-optimizer";

const BASE_AGENT_OPTIMIZER_URL = import.meta.env.VITE_BASE_AGENT_OPTIMIZER_URL || "http://localhost:8000";

type UseAgentOptimizerRunStreamingParams = {
  traceId: string;
};

const isRecord = (val: unknown): val is Record<string, unknown> =>
  isPlainObject(val);

export default function useAgentOptimizerRunStreaming({
  traceId,
}: UseAgentOptimizerRunStreamingParams) {
  return useCallback(
    async ({
      message,
      agentEndpoint,
      response,
      signal,
      onAddChunk,
    }: OptimizerRunStreamingArgs): Promise<OptimizerRunStreamingReturn> => {
      let detectedError: string | null = null;

      try {
        // Determine endpoint based on whether we're sending a response or starting
        const endpoint = response
          ? `${BASE_AGENT_OPTIMIZER_URL}/optimizer/session/${traceId}/response`
          : `${BASE_AGENT_OPTIMIZER_URL}/optimizer/session/${traceId}`;

        const body = response
          ? {
              response_type: response.responseType,
              data: response.data,
            }
          : {
              message: message || "",
              agent_endpoint: agentEndpoint,
              streaming: true,
            };

        const fetchResponse = await fetch(endpoint, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(body),
          credentials: "include",
          signal,
        });

        if (!fetchResponse.ok || !fetchResponse.body) {
          throw new Error("Failed to start streaming response");
        }

        const reader = fetchResponse.body.getReader();
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
              const parsed = JSON.parse(jsonData.trim()) as unknown;

              if (isRecord(parsed)) {
                const data = get(parsed, "data");
                
                if (isRecord(data)) {
                  // Check for error
                  if (data.type === "error" && isString(data.content)) {
                    detectedError = data.content;
                    break;
                  }

                  // Valid message
                  onAddChunk(data as Partial<AgentOptimizerMessage>);
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
