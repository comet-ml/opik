import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import omit from "lodash/omit";

import { getLoggedParameters } from "./createLogPlaygroundProcessor";
import useCompletionProxyStreaming from "./useCompletionProxyStreaming";
import {
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

dayjs.extend(utc);

const configs = (c: Record<string, unknown>) =>
  c as unknown as LLMPromptConfigsType;

const MOCK_MODEL = "custom-llm/mock/mock-model" as PROVIDER_MODEL_TYPE;

const REQUEST_ENVELOPE_KEYS = ["model", "messages", "stream", "stream_options"];

// The config deliberately keeps a parameter the selected model rejects, so that switching back to
// one that accepts it restores the value. The trace must still record what was sent, or it reports
// a temperature the call never ran at — which is the one thing a trace is for.
describe("getLoggedParameters", () => {
  it("omits the sampling params an OpenAI reasoning model never received", () => {
    const parameters = getLoggedParameters({
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      configs: configs({
        temperature: 0.3,
        topP: 0.85,
        maxCompletionTokens: 4000,
      }),
    });

    expect(parameters.temperature).toBeUndefined();
    expect(parameters.top_p).toBeUndefined();
    expect(parameters.max_completion_tokens).toBe(4000);
  });

  it("omits the sampling params an Anthropic model without them never received", () => {
    const parameters = getLoggedParameters({
      model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
      configs: configs({ temperature: 0.3, maxCompletionTokens: 4000 }),
    });

    expect(parameters.temperature).toBeUndefined();
  });

  it("records the reasoning effort the key's pipeline mode let through", () => {
    const stored = configs({
      maxCompletionTokens: 4000,
      reasoningEffort: "max",
    });

    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
        configs: stored,
        openAiPipelineMode: "responses_api",
      }).reasoning_effort,
    ).toBe("max");
    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
        configs: stored,
      }).reasoning_effort,
    ).toBe("high");
  });

  it("records no penalties for a Responses API key, which never sent them", () => {
    const stored = configs({
      temperature: 0.3,
      frequencyPenalty: 0.5,
      presencePenalty: 0.2,
    });

    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        configs: stored,
        openAiPipelineMode: "responses_api",
      }),
    ).toEqual({ temperature: 0.3 });
    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        configs: stored,
        openAiPipelineMode: "chat_completions_api",
      }),
    ).toEqual({
      temperature: 0.3,
      frequency_penalty: 0.5,
      presence_penalty: 0.2,
    });
  });

  it("records what a model that accepts them was sent, under the API names", () => {
    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        configs: configs({
          temperature: 0.3,
          topP: 0.85,
          maxCompletionTokens: 300,
        }),
      }),
    ).toEqual({ temperature: 0.3, top_p: 0.85, max_completion_tokens: 300 });
  });

  it("records the OpenRouter sampling params as the top-level keys they reach OpenRouter as", () => {
    const parameters = getLoggedParameters({
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      configs: configs({
        maxTokens: 512,
        topK: 40,
        minP: 0.1,
        topA: 0.2,
        repetitionPenalty: 1.1,
      }),
    });

    expect(parameters).toEqual({
      max_tokens: 512,
      top_k: 40,
      min_p: 0.1,
      top_a: 0.2,
      repetition_penalty: 1.1,
    });
  });

  it("records no runner settings, which the playground keeps to itself", () => {
    const parameters = getLoggedParameters({
      model: MOCK_MODEL,
      configs: configs({
        temperature: 0.2,
        throttling: 3,
        maxConcurrentRequests: 5,
      }),
    });

    expect(parameters).toEqual({ temperature: 0.2 });
  });

  it.each([
    {
      name: "an extra body key replaces the slider value of the same name",
      extraBody: { temperature: 0.9 },
      expected: { temperature: 0.9, max_completion_tokens: 20, top_p: 1 },
    },
    {
      name: "an extra body max_tokens replaces the max_completion_tokens slider",
      extraBody: { temperature: 0.9, max_tokens: 12 },
      expected: { temperature: 0.9, max_tokens: 12, top_p: 1 },
    },
    {
      name: "an extra body max_completion_tokens replaces the slider",
      extraBody: { max_completion_tokens: 12 },
      expected: { temperature: 0.2, max_completion_tokens: 12, top_p: 1 },
    },
    {
      name: "other extra body keys are recorded next to the sliders",
      extraBody: { top_k: 5, stop: ["END"] },
      expected: {
        temperature: 0.2,
        max_completion_tokens: 20,
        top_p: 1,
        top_k: 5,
        stop: ["END"],
      },
    },
    {
      name: "a cleared extra body records the sliders alone",
      extraBody: null,
      expected: { temperature: 0.2, max_completion_tokens: 20, top_p: 1 },
    },
  ])("$name", ({ extraBody, expected }) => {
    expect(
      getLoggedParameters({
        model: MOCK_MODEL,
        configs: configs({
          temperature: 0.2,
          maxCompletionTokens: 20,
          topP: 1,
          custom_parameters: extraBody,
        }),
      }),
    ).toEqual(expected);
  });

  it.each([
    {
      name: "Ollama gets the slider limit as max_tokens",
      provider: `${PROVIDER_TYPE.OLLAMA}:local`,
      model: "custom-llm/local/llama3.2",
      sliders: {},
      extraBody: null,
      expected: { max_tokens: 20 },
    },
    {
      name: "Ollama gets an extra body max_completion_tokens as max_tokens",
      provider: `${PROVIDER_TYPE.OLLAMA}:local`,
      model: "custom-llm/local/llama3.2",
      sliders: {},
      extraBody: { max_completion_tokens: 12 },
      expected: { max_tokens: 12 },
    },
    {
      name: "Ollama keeps the extra body max_tokens when both names are there",
      provider: `${PROVIDER_TYPE.OLLAMA}:local`,
      model: "custom-llm/local/llama3.2",
      sliders: {},
      extraBody: { max_tokens: 12, max_completion_tokens: 30 },
      expected: { max_tokens: 12 },
    },
    {
      name: "Ollama gets no slider limit of 0",
      provider: `${PROVIDER_TYPE.OLLAMA}:local`,
      model: "custom-llm/local/llama3.2",
      sliders: { maxCompletionTokens: 0 },
      extraBody: null,
      expected: {},
    },
    {
      name: "Bedrock gets an extra body max_tokens as max_completion_tokens",
      provider: `${PROVIDER_TYPE.BEDROCK}:aws`,
      model: "custom-llm/aws/openai.gpt-oss-120b-1:0",
      sliders: {},
      extraBody: { max_tokens: 40 },
      expected: { max_completion_tokens: 40 },
    },
    {
      name: "Bedrock keeps the extra body max_completion_tokens when both names are there",
      provider: `${PROVIDER_TYPE.BEDROCK}:aws`,
      model: "custom-llm/aws/openai.gpt-oss-120b-1:0",
      sliders: {},
      extraBody: { max_tokens: 40, max_completion_tokens: 30 },
      expected: { max_completion_tokens: 30 },
    },
    {
      name: "Bedrock gets no slider penalty of 0",
      provider: `${PROVIDER_TYPE.BEDROCK}:aws`,
      model: "custom-llm/aws/openai.gpt-oss-120b-1:0",
      sliders: { frequencyPenalty: 0, presencePenalty: 0.5 },
      extraBody: null,
      expected: { max_completion_tokens: 20, presence_penalty: 0.5 },
    },
    {
      name: "a Custom LLM gets both token limit names as typed",
      provider: `${PROVIDER_TYPE.CUSTOM}:mock`,
      model: MOCK_MODEL,
      sliders: { frequencyPenalty: 0 },
      extraBody: { max_tokens: 12, max_completion_tokens: 30 },
      expected: {
        max_tokens: 12,
        max_completion_tokens: 30,
        frequency_penalty: 0,
      },
    },
  ])("$name", ({ provider, model, sliders, extraBody, expected }) => {
    expect(
      getLoggedParameters({
        provider,
        model: model as PROVIDER_MODEL_TYPE,
        configs: configs({
          maxCompletionTokens: 20,
          ...sliders,
          custom_parameters: extraBody,
        }),
      }),
    ).toEqual(expected);
  });
});

describe("getLoggedParameters against the body the playground sends", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  const sentBody = async (
    model: PROVIDER_MODEL_TYPE,
    stored: LLMPromptConfigsType,
  ) => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(""));
    const { result } = renderHook(() =>
      useCompletionProxyStreaming({ workspaceName: "default" }),
    );

    await result.current({
      model,
      messages: [{ role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      configs: stored,
      onAddChunk: vi.fn(),
      signal: new AbortController().signal,
    });

    const [, init] = fetchSpy.mock.calls[0];
    return JSON.parse(init?.body as string);
  };

  it("records exactly the model parameters an OpenAI request carried", async () => {
    const stored = configs({
      temperature: 0.3,
      maxCompletionTokens: 300,
      topP: 0.9,
      frequencyPenalty: 0.1,
      presencePenalty: 0.2,
      throttling: 0,
      maxConcurrentRequests: 5,
    });
    const body = await sentBody(PROVIDER_MODEL_TYPE.GPT_4O, stored);

    expect(body).not.toHaveProperty("throttling");
    expect(body).not.toHaveProperty("max_concurrent_requests");
    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        configs: stored,
      }),
    ).toEqual(omit(body, REQUEST_ENVELOPE_KEYS));
  });

  it("records a Custom LLM request with its extra body applied, as the backend sends it", async () => {
    const stored = configs({
      temperature: 0.2,
      maxCompletionTokens: 20,
      topP: 1,
      custom_parameters: { temperature: 0.9, max_tokens: 12, top_k: 5 },
      throttling: 0,
      maxConcurrentRequests: 5,
    });
    const body = await sentBody(MOCK_MODEL, stored);

    expect(omit(body, REQUEST_ENVELOPE_KEYS)).toEqual({
      temperature: 0.2,
      max_completion_tokens: 20,
      top_p: 1,
      custom_parameters: { temperature: 0.9, max_tokens: 12, top_k: 5 },
    });
    expect(getLoggedParameters({ model: MOCK_MODEL, configs: stored })).toEqual(
      { temperature: 0.9, max_tokens: 12, top_p: 1, top_k: 5 },
    );
  });
});
