import { describe, expect, it } from "vitest";

import { PROVIDER_TYPE } from "@/types/providers";
import { sortProviderModels } from "./useModelOptions";

describe("sortProviderModels", () => {
  it("keeps non-OpenRouter providers unchanged", () => {
    const models = [
      { label: "z-model", value: "z-model" },
      { label: "a-model", value: "a-model" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.OPEN_AI, models)).toEqual(models);
  });

  it("sorts OpenRouter models with openrouter/* routes first and A-Z", () => {
    const models = [
      { label: "zeta", value: "zeta/model" },
      { label: "openrouter/free", value: "openrouter/free" },
      { label: "alpha", value: "alpha/model" },
      { label: "openrouter/auto", value: "openrouter/auto" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.OPEN_ROUTER, models)).toEqual([
      { label: "openrouter/auto", value: "openrouter/auto" },
      { label: "openrouter/free", value: "openrouter/free" },
      { label: "alpha", value: "alpha/model" },
      { label: "zeta", value: "zeta/model" },
    ]);
  });

  it("sorts OrcaRouter models with bare routers first and A-Z", () => {
    const models = [
      { label: "orcarouter/openai/gpt-4o-mini", value: "orcarouter/openai/gpt-4o-mini" },
      { label: "orcarouter/auto", value: "orcarouter/auto" },
      { label: "orcarouter/anthropic/claude-opus-4.8", value: "orcarouter/anthropic/claude-opus-4.8" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.ORCA_ROUTER, models)).toEqual([
      { label: "orcarouter/auto", value: "orcarouter/auto" },
      {
        label: "orcarouter/anthropic/claude-opus-4.8",
        value: "orcarouter/anthropic/claude-opus-4.8",
      },
      {
        label: "orcarouter/openai/gpt-4o-mini",
        value: "orcarouter/openai/gpt-4o-mini",
      },
    ]);
  });
});
