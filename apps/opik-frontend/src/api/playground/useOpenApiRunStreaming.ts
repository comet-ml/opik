import { useCallback, useRef } from "react";
import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundPromptConfigsType,
  ProviderMessageType,
  ProviderStreamingMessageChoiceType,
  ProviderStreamingMessageType,
} from "@/types/playgroundPrompts";
import { safelyParseJSON, snakeCaseObj } from "@/lib/utils";
import dayjs from "dayjs";
import { UsageType } from "@/types/shared";

interface GetOpenAIStreamParams {
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  signal: AbortSignal;
  configs: PlaygroundPromptConfigsType;
}

const getOpenAIStream = async ({
  model,
  messages,
  signal,
  configs,
}: GetOpenAIStreamParams) => {
  const apiKey = window.localStorage.getItem("OPENAI_API_KEY") || "";

  return fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      messages,
      stream: true,
      stream_options: { include_usage: true },
      ...snakeCaseObj(configs),
    }),
    signal: signal,
  });
};

const getResponseError = async (response: Response) => {
  let error;

  try {
    error = (await response?.json())?.error?.message;
  } catch {
    error = "Unexpected error occurred.";
  }

  return error;
};

export interface RunStreamingReturn {
  error: null | string;
  result: null | string;
  startTime: string;
  endTime: string;
  usage: UsageType | null;
  choices: ProviderStreamingMessageChoiceType[] | null;
}

interface UseOpenApiRunStreamingParameters {
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  onAddChunk: (accumulatedValue: string) => void;
  onLoading: (v: boolean) => void;
  onError: (errMsg: string | null) => void;
  configs: PlaygroundPromptConfigsType;
}

const useOpenApiRunStreaming = ({
  model,
  messages,
  configs,
  onAddChunk,
  onLoading,
  onError,
}: UseOpenApiRunStreamingParameters) => {
  const abortControllerRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
  }, []);

  const runStreaming = useCallback(async (): Promise<RunStreamingReturn> => {
    const startTime = dayjs().utc().toISOString();
    let accumulatedValue = "";
    let usage = null;
    let choices: ProviderStreamingMessageChoiceType[] = [];

    onLoading(true);
    onError(null);

    try {
      abortControllerRef.current = new AbortController();

      const response = await getOpenAIStream({
        model,
        messages,
        configs,
        signal: abortControllerRef.current?.signal,
      });

      if (!response.ok || !response) {
        const error = await getResponseError(response);
        onError(error);
        onLoading(false);

        const endTime = dayjs().utc().toISOString();

        return {
          error,
          result: null,
          startTime,
          endTime,
          usage: null,
          choices: null,
        };
      }

      const reader = response?.body?.getReader();
      const decoder = new TextDecoder("utf-8");

      let done = false;

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
                break;
              }

              const parsed = safelyParseJSON(
                data,
              ) as ProviderStreamingMessageType;

              choices = parsed?.choices;
              const deltaContent = choices?.[0]?.delta?.content;

              if (parsed?.usage) {
                usage = parsed.usage as UsageType;
              }

              if (deltaContent) {
                accumulatedValue += deltaContent;
                onAddChunk(accumulatedValue);
              }
            }
          }
        }
      }
      //   abort signal also jumps into here
    } finally {
      onLoading(false);
    }

    const endTime = dayjs().utc().toISOString();

    return {
      startTime,
      endTime,
      result: accumulatedValue,
      error: null,
      usage,
      choices,
    };
  }, [messages, model, onAddChunk, onError, onLoading, configs]);

  return { runStreaming, stop };
};

export default useOpenApiRunStreaming;
