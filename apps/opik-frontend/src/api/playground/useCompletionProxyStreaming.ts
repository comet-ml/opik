import { useCallback, useRef } from "react";

import dayjs from "dayjs";
import { UsageType } from "@/types/shared";
import {
  PlaygroundPromptConfigsType,
  ProviderMessageType,
  ChatCompletionMessageChoiceType,
  ChatCompletionResponse,
  ChatCompletionErrorMessageType,
  ChatCompletionSuccessMessageType,
  ChatCompletionProxyErrorMessageType,
} from "@/types/playground";
import { safelyParseJSON, snakeCaseObj } from "@/lib/utils";
import { BASE_API_URL } from "@/api/api";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

interface GetCompletionProxyStreamParams {
  model: PROVIDER_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  signal: AbortSignal;
  configs: PlaygroundPromptConfigsType;
  workspaceName: string;
}

const getCompletionProxyStream = async ({
  model,
  messages,
  signal,
  configs,
  workspaceName,
}: GetCompletionProxyStreamParams) => {
  return fetch(`${BASE_API_URL}/v1/private/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Comet-Workspace": workspaceName,
    },
    body: JSON.stringify({
      model,
      messages,
      stream: true,
      stream_options: { include_usage: true },
      ...snakeCaseObj(configs),
    }),
    credentials: "include",
    signal,
  });
};

export interface RunStreamingReturn {
  result: null | string;
  startTime: string;
  endTime: string;
  usage: UsageType | null;
  choices: ChatCompletionMessageChoiceType[] | null;
  platformError: null | string;
  proxyError: null | string;
}

interface UseCompletionProxyStreamingParameters {
  model: PROVIDER_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  onAddChunk: (accumulatedValue: string) => void;
  configs: PlaygroundPromptConfigsType;
  workspaceName: string;
}

const useCompletionProxyStreaming = ({
  model,
  messages,
  configs,
  onAddChunk,
  workspaceName,
}: UseCompletionProxyStreamingParameters) => {
  const abortControllerRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
  }, []);

  const runStreaming = useCallback(async (): Promise<RunStreamingReturn> => {
    const startTime = dayjs().utc().toISOString();

    let accumulatedValue = "";
    let usage = null;
    let choices: ChatCompletionMessageChoiceType[] = [];

    // errors
    let proxyError = null;
    let platformError = null;

    try {
      abortControllerRef.current = new AbortController();

      const response = await getCompletionProxyStream({
        model,
        messages,
        configs,
        signal: abortControllerRef.current?.signal,
        workspaceName,
      });

      const reader = response?.body?.getReader();
      const decoder = new TextDecoder("utf-8");

      const handleSuccessMessage = (
        parsed: ChatCompletionSuccessMessageType,
      ) => {
        choices = parsed?.choices;
        const deltaContent = choices?.[0]?.delta?.content;

        if (parsed?.usage) {
          usage = parsed.usage as UsageType;
        }

        if (deltaContent) {
          accumulatedValue += deltaContent;
          onAddChunk(accumulatedValue);
        }
      };

      const handleAIPlatformErrorMessage = (
        parsedMessage: ChatCompletionErrorMessageType,
      ) => {
        const message = safelyParseJSON(parsedMessage?.message);

        platformError = message?.error?.message;
      };

      const handleProxyErrorMessage = (
        parsedMessage: ChatCompletionProxyErrorMessageType,
      ) => {
        proxyError = parsedMessage.errors.join(" ");
      };

      while (true) {
        if (!reader) {
          break;
        }

        const { done, value } = await reader.read();

        if (done || proxyError || platformError) {
          break;
        }

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split("\n").filter((line) => line.trim() !== "");

        for (const line of lines) {
          const parsed = safelyParseJSON(line) as ChatCompletionResponse;

          // handle different message types
          if ("errors" in parsed) {
            handleProxyErrorMessage(parsed);
          } else if ("code" in parsed) {
            handleAIPlatformErrorMessage(parsed);
          } else {
            handleSuccessMessage(parsed);
          }
        }
      }
      return {
        startTime,
        endTime: dayjs().utc().toISOString(),
        result: accumulatedValue,
        platformError,
        proxyError,
        usage,
        choices,
      };
      //   abort signal also jumps into here
    } catch (error) {
      const typedError = error as Error;
      const isStopped = typedError.name === "AbortError";

      // no error if a run has been stopped
      const defaultErrorMessage = isStopped ? null : "Unexpected error";

      return {
        startTime,
        endTime: dayjs().utc().toISOString(),
        result: accumulatedValue,
        platformError,
        proxyError: proxyError || defaultErrorMessage,
        usage: null,
        choices,
      };
    }
  }, [messages, model, onAddChunk, configs]);

  return { runStreaming, stop };
};

export default useCompletionProxyStreaming;
