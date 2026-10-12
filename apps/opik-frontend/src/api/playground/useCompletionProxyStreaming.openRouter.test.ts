import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import useCompletionProxyStreaming from "./useCompletionProxyStreaming";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMOpenRouterConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { getDefaultConfigByProvider } from "@/lib/playground";

// The app registers this plugin at startup (lib/date.ts); the hook timestamps every run with it.
dayjs.extend(utc);

describe("the body an OpenRouter playground run sends", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  const sentBody = async (configs: Partial<LLMOpenRouterConfigsType>) => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(""));
    const { result } = renderHook(() =>
      useCompletionProxyStreaming({ workspaceName: "default" }),
    );

    await result.current({
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      messages: [{ role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      configs: configs as LLMOpenRouterConfigsType,
      onAddChunk: vi.fn(),
      signal: new AbortController().signal,
    });

    const [, init] = fetchSpy.mock.calls[0];
    return JSON.parse(init?.body as string);
  };

  it("nests the parameters the backend has no field for under custom_parameters", async () => {
    const body = await sentBody({
      maxTokens: 512,
      temperature: 0.7,
      topK: 39.6,
      minP: 0.1,
      topA: 0.2,
      repetitionPenalty: 1.1,
    });

    expect(body).toMatchObject({
      max_tokens: 512,
      temperature: 0.7,
      custom_parameters: {
        top_k: 40,
        min_p: 0.1,
        top_a: 0.2,
        repetition_penalty: 1.1,
      },
    });
    for (const flat of ["top_k", "min_p", "top_a", "repetition_penalty"]) {
      expect(body).not.toHaveProperty(flat);
    }
  });

  it("sends a new prompt's default temperature of 0", async () => {
    const body = await sentBody(
      getDefaultConfigByProvider(
        PROVIDER_TYPE.OPEN_ROUTER as COMPOSED_PROVIDER_TYPE,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      ) as LLMOpenRouterConfigsType,
    );

    expect(body).toHaveProperty("temperature", 0);
  });

  it("sends no max_tokens for 0", async () => {
    expect(await sentBody({ maxTokens: 0, topK: 40 })).not.toHaveProperty(
      "max_tokens",
    );
  });
});
