import { useCallback } from "react";
import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import { safelyParseJSON } from "@/lib/utils";

interface UseOpenApiRunStreamingParameters {
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: PlaygroundMessageType[];
  onAddChunk: (accumulatedValue: string) => void;
  onLoading: (v: boolean) => void;
  onError: (errMsg: string | null) => void;
}

const useOpenApiRunStreaming = ({
  model,
  messages,
  onAddChunk,
  onLoading,
  onError,
}: UseOpenApiRunStreamingParameters) => {
  const runStreaming = useCallback(async () => {
    const apiKey = window.localStorage.getItem("OPENAI_API_KEY") || "";

    onLoading(true);
    onError(null);

    // axios doesn't support stream chunks
    const response = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model,
        messages: messages.map((m) => ({
          // ALEX: rename them later
          role: m.type,
          content: m.text,
        })),
        stream: true,
      }),
    });

    if (!response.ok || !response) {
      onLoading(false);

      let error = "Unexpected error occurred.";

      try {
        error = (await response?.json())?.error?.message;
      } finally {
        onError(error);
      }

      return;
    }

    const reader = response?.body?.getReader();
    const decoder = new TextDecoder("utf-8");
    let done = false;
    let partial = "";

    while (!done && reader) {
      const { value, done: doneReading } = await reader.read();

      done = doneReading;

      if (value) {
        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split("\n").filter((line) => line.trim() !== "");

        for (const line of lines) {
          if (line.startsWith("data:")) {
            const data = line.replace(/^data:\s*/, "");

            // stream finished
            if (data === "[DONE]") {
              return;
            }

            const parsed = safelyParseJSON(data);
            const token = parsed?.choices?.[0]?.delta?.content;

            if (token) {
              partial += token;
              onAddChunk(partial);
            }
          }
        }
      }
    }

    onLoading(false);
  }, [messages, model, onAddChunk, onError, onLoading]);

  return runStreaming;
};

export default useOpenApiRunStreaming;
