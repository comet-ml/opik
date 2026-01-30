import { useCallback } from "react";
import { isPlainObject, get, isString } from "lodash";
import type {
  OptimizerRunStreamingArgs,
  OptimizerRunStreamingReturn,
  AgentOptimizerMessage,
} from "@/types/agent-optimizer";

const BASE_AGENT_OPTIMIZER_URL =
  import.meta.env.VITE_BASE_AGENT_OPTIMIZER_URL || "http://localhost:5000";
const AGENT_ENDPOINT_URL =
  import.meta.env.VITE_AGENT_ENDPOINT_URL || "http://localhost:8001/chat";

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
              agent_endpoint: agentEndpoint || AGENT_ENDPOINT_URL,
              streaming: true,
            };

        console.log(
          "[useAgentOptimizerRunStreaming] Sending request to:",
          endpoint,
        );
        console.log(
          "[useAgentOptimizerRunStreaming] Request body:",
          JSON.stringify(body),
        );

        const fetchResponse = await fetch(endpoint, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(body),
          credentials: "include",
          signal,
        });

        console.log(
          "[useAgentOptimizerRunStreaming] Response status:",
          fetchResponse.status,
          "OK:",
          fetchResponse.ok,
        );

        if (!fetchResponse.ok || !fetchResponse.body) {
          const errorText = await fetchResponse
            .text()
            .catch(() => "Unable to read error");
          console.error(
            "[useAgentOptimizerRunStreaming] Stream failed:",
            fetchResponse.status,
            errorText,
          );
          throw new Error(
            `Failed to start streaming response: ${fetchResponse.status} - ${errorText}`,
          );
        }

        const reader = fetchResponse.body.getReader();
        const decoder = new TextDecoder("utf-8");
        console.log(
          "[useAgentOptimizerRunStreaming] Starting to read stream...",
        );

        let chunkCount = 0;
        while (reader) {
          const { done, value } = await reader.read();
          if (done) {
            console.log(
              "[useAgentOptimizerRunStreaming] Stream completed. Total chunks read:",
              chunkCount,
            );
            break;
          }

          chunkCount++;
          const chunk = decoder.decode(value, { stream: true });
          console.log(
            "[useAgentOptimizerRunStreaming] Read chunk #",
            chunkCount,
            "Size:",
            value?.length,
            "bytes",
          );
          console.log("[useAgentOptimizerRunStreaming] Chunk content:", chunk);
          const lines = chunk.split("\n").filter((line) => line.trim() !== "");
          console.log(
            "[useAgentOptimizerRunStreaming] Parsed",
            lines.length,
            "lines from chunk",
          );

          for (const line of lines) {
            // Skip event: lines in SSE format
            if (line.trim().startsWith("event:")) {
              continue;
            }

            const jsonData = line.startsWith("data:")
              ? line.split("data:")[1]
              : line;

            try {
              // First, try to parse as standard JSON
              let parsed: unknown;
              const trimmedData = jsonData.trim();

              try {
                parsed = JSON.parse(trimmedData);
              } catch (jsonError) {
                // If standard JSON parsing fails, try Python dict format conversion
                // This is a workaround for backend sending Python repr() instead of JSON
                try {
                  // More careful Python-to-JSON conversion:
                  // 1. First, temporarily replace escaped single quotes with a placeholder
                  // 2. Then replace single quotes with double quotes
                  // 3. Finally, restore escaped quotes as regular single quotes
                  // eslint-disable-next-line no-control-regex
                  const PLACEHOLDER = "\u0000ESCAPED_QUOTE\u0000";
                  const jsonString = trimmedData
                    .replace(/\\'/g, PLACEHOLDER) // Protect escaped single quotes
                    .replace(/'/g, '"') // Replace single quotes with double quotes
                    .replace(new RegExp(PLACEHOLDER, "g"), "'") // Restore as regular single quotes
                    .replace(/True/g, "true") // Python True -> JSON true
                    .replace(/False/g, "false") // Python False -> JSON false
                    .replace(/None/g, "null"); // Python None -> JSON null

                  parsed = JSON.parse(jsonString);
                } catch (pythonConvertError) {
                  console.warn(
                    "[useAgentOptimizerRunStreaming] Failed to parse line as JSON or Python dict:",
                    trimmedData.substring(0, 200),
                  );
                  continue;
                }
              }

              if (isRecord(parsed)) {
                // Try to get data from wrapped format first, otherwise use parsed directly
                const data = get(parsed, "data", parsed);

                if (isRecord(data)) {
                  console.log(
                    "[useAgentOptimizerRunStreaming] Received chunk - ID:",
                    data.id,
                    "Type:",
                    data.type,
                  );

                  // Check for error
                  if (data.type === "error" && isString(data.content)) {
                    detectedError = data.content;
                    break;
                  }

                  // Valid message
                  onAddChunk(data as Partial<AgentOptimizerMessage>);
                }
              }
            } catch (err) {
              console.warn(
                "[useAgentOptimizerRunStreaming] Error processing line:",
                err,
              );
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
