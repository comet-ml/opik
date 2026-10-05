import { describe, expect, it } from "vitest";

import { getLoggedParameters } from "./createLogPlaygroundProcessor";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";

const configs = (c: Record<string, unknown>) =>
  c as unknown as LLMPromptConfigsType;

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
    expect(parameters.topP).toBeUndefined();
    expect(parameters.maxCompletionTokens).toBe(4000);
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
      }).reasoningEffort,
    ).toBe("max");
    expect(
      getLoggedParameters({
        model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
        configs: stored,
      }).reasoningEffort,
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
      frequencyPenalty: 0.5,
      presencePenalty: 0.2,
    });
  });

  it("records what a model that accepts them was sent", () => {
    const parameters = getLoggedParameters({
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      configs: configs({ temperature: 0.3, topP: 0.85 }),
    });

    expect(parameters.temperature).toBe(0.3);
    expect(parameters.topP).toBe(0.85);
  });
});
