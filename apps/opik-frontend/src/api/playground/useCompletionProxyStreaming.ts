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

/**
 * Processes SSE chunk data with buffering for incomplete lines.
 * SSE messages can be split across network chunks, so we buffer incomplete
 * lines and only process complete lines (those ending with newline).
 */
export const processSSEChunk = (
  chunk: string,
  buffer: string,
): { lines: string[]; newBuffer: string } => {
  const data = buffer + chunk;
  const lines = data.split("\n");

  // if the data doesn't end with newline, the last element is incomplete
  // save it for the next iteration
  let newBuffer = "";
  if (!data.endsWith("\n")) {
    newBuffer = lines.pop() || "";
  }

  const completeLines = lines.filter((line) => line.trim() !== "");

  return { lines: completeLines, newBuffer };
};

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
  const configsRecord = { ...configs } as Record<string, unknown>;

  // Anthropic rejects requests containing both temperature and top_p
  if (
    model.includes("claude") &&
    configsRecord.topP != null &&
    configsRecord.temperature != null
  ) {
    delete configsRecord.topP;
  }

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
      ...snakeCaseObj(configsRecord),
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
  // Resolved model and provider from headers (for span tracking)
  actualModel: string | null;
  actualProvider: string | null;
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

      // Resolved model/provider from headers
      let actualModel: string | null = null;
      let actualProvider: string | null = null;

      try {
        const response = await getCompletionProxyStream({
          model,
          messages,
          configs,
          signal,
          workspaceName,
        });

        // Extract resolved model and provider from headers
        actualModel = response.headers.get("X-Opik-Actual-Model");
        actualProvider = response.headers.get("X-Opik-Provider");

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

        // buffer to hold incomplete lines across chunks
        let lineBuffer = "";

        // an analogue of true && reader
        // we need it to wait till the stream is closed
        while (reader) {
          const { done, value } = await reader.read();

          if (done || opikError || pythonProxyError || providerError) {
            break;
          }

          const chunk = decoder.decode(value, { stream: true });
          const { lines, newBuffer } = processSSEChunk(chunk, lineBuffer);
          lineBuffer = newBuffer;

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
          actualModel,
          actualProvider,
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
          actualModel,
          actualProvider,
        };
      }
    },
    [workspaceName],
  );
};

export default useCompletionProxyStreaming;
