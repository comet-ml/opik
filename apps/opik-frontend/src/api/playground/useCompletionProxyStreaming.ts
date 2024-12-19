import { useCallback, useEffect, useRef } from "react";

import dayjs from "dayjs";
import { UsageType } from "@/types/shared";
import {
  PlaygroundPromptConfigsType,
  ProviderMessageType,
  ChatCompletionMessageChoiceType,
  ChatCompletionResponse,
  ChatCompletionProviderErrorMessageType,
  ChatCompletionSuccessMessageType,
  ChatCompletionOpikErrorMessageType,
} from "@/types/playground";
import { isValidJsonObject, safelyParseJSON, snakeCaseObj } from "@/lib/utils";
import { BASE_API_URL } from "@/api/api";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { useLocation } from "@tanstack/react-router";

const getNowUtcTimeISOString = (): string => {
  return dayjs().utc().toISOString();
};

interface GetCompletionProxyStreamParams {
  model: PROVIDER_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  signal: AbortSignal;
  configs: PlaygroundPromptConfigsType;
  workspaceName: string;
}

const isOpikError = (
  response: ChatCompletionResponse,
): response is ChatCompletionOpikErrorMessageType => {
  return (
    "errors" in response ||
    ("code" in response && !isValidJsonObject(response.message))
  );
};

const isProviderError = (
  response: ChatCompletionResponse,
): response is ChatCompletionProviderErrorMessageType => {
  return "code" in response && isValidJsonObject(response.message);
};

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
  providerError: null | string;
  opikError: null | string;
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
    const startTime = getNowUtcTimeISOString();

    let accumulatedValue = "";
    let usage = null;
    let choices: ChatCompletionMessageChoiceType[] = [];

    // errors
    let opikError = null;
    let providerError = null;

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
        parsedMessage: ChatCompletionProviderErrorMessageType,
      ) => {
        const message = safelyParseJSON(parsedMessage?.message);

        providerError = message?.error?.message;
      };

      const handleOpikErrorMessage = (
        parsedMessage: ChatCompletionOpikErrorMessageType,
      ) => {
        if ("code" in parsedMessage && "message" in parsedMessage) {
          opikError = parsedMessage.message;
          return;
        }

        opikError = parsedMessage.errors.join(" ");
      };

      // an analogue of true && reader
      // we need it to wait till the stream is closed
      while (reader) {
        const { done, value } = await reader.read();

        if (done || opikError || providerError) {
          break;
        }

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split("\n").filter((line) => line.trim() !== "");

        for (const line of lines) {
          const parsed = safelyParseJSON(line) as ChatCompletionResponse;

          // handle different message types
          if (isOpikError(parsed)) {
            handleOpikErrorMessage(parsed);
          } else if (isProviderError(parsed)) {
            handleAIPlatformErrorMessage(parsed);
          } else {
            handleSuccessMessage(parsed);
          }
        }
      }
      return {
        startTime,
        endTime: getNowUtcTimeISOString(),
        result: accumulatedValue,
        providerError,
        opikError,
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
        endTime: getNowUtcTimeISOString(),
        result: accumulatedValue,
        providerError,
        opikError: opikError || defaultErrorMessage,
        usage: null,
        choices,
      };
    }
  }, [messages, model, onAddChunk, configs, workspaceName]);

  const location = useLocation();

  useEffect(() => {
    // stop streaming whenever the location changes
    return () => stop();
  }, [location, stop]);

  return { runStreaming, stop };
};

export default useCompletionProxyStreaming;
