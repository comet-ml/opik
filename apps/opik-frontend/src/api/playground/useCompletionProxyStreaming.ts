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

const DATA_PREFIX = "data:";

const getNowUtcTimeISOString = (): string => {
  return dayjs().utc().toISOString();
};

interface GetCompletionProxyStreamParams {
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

export interface RunStreamingArgs {
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
          model,
          messages,
          configs,
          signal,
          workspaceName,
        });

        // Check if the response is OK before trying to read the stream
        if (!response.ok) {
          const errorText = await response.text().catch(() => "Unknown error");
          opikError = `Request failed with status ${response.status}: ${errorText}`;
          return {
            startTime,
            endTime: getNowUtcTimeISOString(),
            result: accumulatedValue,
            providerError,
            opikError,
            pythonProxyError,
            usage: null,
            choices,
          };
        }

        if (!response.body) {
          opikError = "No response body received from server";
          return {
            startTime,
            endTime: getNowUtcTimeISOString(),
            result: accumulatedValue,
            providerError,
            opikError,
            pythonProxyError,
            usage: null,
            choices,
          };
        }

        const reader = response.body.getReader();
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
            // Make "Unexpected error calling LLM provider" more user-friendly
            const message = parsedMessage.message;
            if (message === "Unexpected error calling LLM provider") {
              opikError =
                "The AI provider encountered an error. This may be due to network issues, rate limits, or service unavailability. Please try again.";
            } else {
              opikError = message;
            }
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
            const JSONData = line.startsWith(DATA_PREFIX)
              ? line.split(DATA_PREFIX)[1]
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

        // Handle different types of errors
        let errorMessage: string | null = null;
        if (!isStopped) {
          if (
            typedError.name === "TypeError" &&
            typedError.message.includes("fetch")
          ) {
            errorMessage =
              "Network error: Unable to connect to the AI provider. Please check your connection and try again.";
          } else if (
            typedError.message.includes("timeout") ||
            typedError.message.includes("Timeout")
          ) {
            errorMessage =
              "Request timed out. The AI provider took too long to respond. This may happen with large requests. Please try again.";
          } else {
            errorMessage =
              typedError.message ||
              "An unexpected error occurred while calling the AI provider.";
          }
        }

        return {
          startTime,
          endTime: getNowUtcTimeISOString(),
          result: accumulatedValue,
          providerError,
          opikError: opikError || errorMessage,
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
