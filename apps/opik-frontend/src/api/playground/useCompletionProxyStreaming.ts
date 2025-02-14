import { useCallback } from "react";
import dayjs from "dayjs";
import isObject from "lodash/isObject";

import { UsageType } from "@/types/shared";
import {
  ChatCompletionMessageChoiceType,
  ChatCompletionResponse,
  ChatCompletionProviderErrorMessageType,
  ChatCompletionSuccessMessageType,
  ChatCompletionOpikErrorMessageType,
  ChatCompletionPythonProxyErrorMessageType,
} from "@/types/playground";
import { isValidJsonObject, safelyParseJSON, snakeCaseObj } from "@/lib/utils";
import { BASE_API_URL } from "@/api/api";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { ProviderMessageType } from "@/types/llm";

const getNowUtcTimeISOString = (): string => {
  return dayjs().utc().toISOString();
};

interface GetCompletionProxyStreamParams {
  url?: string;
  model: PROVIDER_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  signal: AbortSignal;
  configs: LLMPromptConfigsType;
  workspaceName: string;
}

const isPythonProxyError = (
  response: ChatCompletionResponse,
): response is ChatCompletionPythonProxyErrorMessageType => {
  return "detail" in response;
};

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
  url = `${BASE_API_URL}/v1/private/chat/completions`,
  model,
  messages,
  signal,
  configs,
  workspaceName,
}: GetCompletionProxyStreamParams) => {
  return fetch(url, {
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

export interface RunStreamingArgs {
  url?: string;
  model: PROVIDER_MODEL_TYPE | "";
  messages: ProviderMessageType[];
  configs: LLMPromptConfigsType;
  onAddChunk: (accumulatedValue: string) => void;
  signal: AbortSignal;
}

export interface RunStreamingReturn {
  result: null | string;
  startTime: string;
  endTime: string;
  usage: UsageType | null;
  choices: ChatCompletionMessageChoiceType[] | null;
  providerError: null | string;
  opikError: null | string;
  pythonProxyError: null | string;
}

interface UseCompletionProxyStreamingParameters {
  workspaceName: string;
}

const useCompletionProxyStreaming = ({
  workspaceName,
}: UseCompletionProxyStreamingParameters) => {
  return useCallback(
    async ({
      url,
      model,
      messages,
      configs,
      onAddChunk,
      signal,
    }: RunStreamingArgs): Promise<RunStreamingReturn> => {
      const startTime = getNowUtcTimeISOString();

      let accumulatedValue = "";
      let usage = null;
      let choices: ChatCompletionMessageChoiceType[] = [];

      // errors
      let pythonProxyError = null;
      let opikError = null;
      let providerError = null;

      try {
        const response = await getCompletionProxyStream({
          url,
          model,
          messages,
          configs,
          signal,
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

        const handlePythonProxyErrorMessage = (
          parsedMessage: ChatCompletionPythonProxyErrorMessageType,
        ) => {
          if (
            isObject(parsedMessage.detail) &&
            "error" in parsedMessage.detail
          ) {
            pythonProxyError = parsedMessage.detail.error;
          } else {
            pythonProxyError = parsedMessage.detail ?? "Python proxy error";
          }
        };

        // an analogue of true && reader
        // we need it to wait till the stream is closed
        while (reader) {
          const { done, value } = await reader.read();

          if (done || opikError || pythonProxyError || providerError) {
            break;
          }

          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split("\n").filter((line) => line.trim() !== "");

          for (const line of lines) {
            const ollamaDataPrefix = "data:";
            const JSONData = line.startsWith(ollamaDataPrefix)
              ? line.split(ollamaDataPrefix)[1]
              : line;

            const parsed = safelyParseJSON(JSONData) as ChatCompletionResponse;

            // handle different message types
            if (isPythonProxyError(parsed)) {
              handlePythonProxyErrorMessage(parsed);
            } else if (isOpikError(parsed)) {
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
          pythonProxyError,
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
          pythonProxyError,
          usage: null,
          choices,
        };
      }
    },
    [workspaceName],
  );
};

export default useCompletionProxyStreaming;
