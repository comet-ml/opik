import axios from "axios";

import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { QueryConfig } from "@/api/api";

type UsePlaygroundPromptRunParams = {
  messages: PlaygroundMessageType[];
  model: PLAYGROUND_MODEL_TYPE;
  promptId: string;
  apiKey?: string;
};

type OpenAIMessage = {
  content: string;
};

type OpenAINoStreamChoice = {
  finish_reason: string;
  message: OpenAIMessage;
};

type OpenAINoStreamResponse = {
  choices: OpenAINoStreamChoice[];
};

const getPlaygroundPromptRun = async (
  { signal }: QueryFunctionContext,
  { messages, model, apiKey }: UsePlaygroundPromptRunParams,
) => {
  // @ToDo: replace it with Proxy
  const response = await axios.post<OpenAINoStreamResponse>(
    "https://api.openai.com/v1/chat/completions",
    {
      model,
      stream: false,
      messages: messages.map((m) => ({
        // ALEX: rename them later
        role: m.type,
        content: m.text,
      })),
    },
    {
      signal,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
    },
  );

  return response.data;
};

const usePlaygroundPromptRun = (
  params: UsePlaygroundPromptRunParams,
  options?: QueryConfig<OpenAINoStreamResponse>,
) => {
  const apiKey = window.localStorage.getItem("OPEN_AI_API_KEY") || "";

  return useQuery({
    queryKey: ["playground-run", params],
    queryFn: (context) =>
      getPlaygroundPromptRun(context, { ...params, apiKey }),
    ...options,
  });
};

export default usePlaygroundPromptRun;
