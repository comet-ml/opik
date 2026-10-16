import { describe, expect, it } from "vitest";

import { mergeProviderModels } from "./mergeProviderModels";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";

const model = (value: string) => ({
  value: value as PROVIDER_MODEL_TYPE,
  label: value,
});

const registry: ProviderModelsMap = {
  [PROVIDER_TYPE.OPEN_ROUTER]: [model("openrouter/auto")],
  [PROVIDER_TYPE.OPEN_AI]: [model("gpt-4o")],
};

describe("mergeProviderModels", () => {
  it("returns the registry map unchanged when nothing is added", () => {
    expect(mergeProviderModels(registry)).toBe(registry);
  });

  it("appends the extra models to their provider and keeps the others", () => {
    const merged = mergeProviderModels(registry, {
      [PROVIDER_TYPE.OPEN_ROUTER]: [model("~typesafe/jev-latest")],
    });

    expect(merged[PROVIDER_TYPE.OPEN_ROUTER].map((m) => m.value)).toEqual([
      "openrouter/auto",
      "~typesafe/jev-latest",
    ]);
    expect(merged[PROVIDER_TYPE.OPEN_AI]).toBe(registry[PROVIDER_TYPE.OPEN_AI]);
  });

  it("adds a provider missing from the registry", () => {
    const merged = mergeProviderModels(
      {},
      {
        [PROVIDER_TYPE.OPEN_ROUTER]: [model("~typesafe/jev-latest")],
      },
    );

    expect(merged[PROVIDER_TYPE.OPEN_ROUTER]).toHaveLength(1);
  });

  it("skips a model the registry already lists", () => {
    const merged = mergeProviderModels(registry, {
      [PROVIDER_TYPE.OPEN_ROUTER]: [model("openrouter/auto")],
    });

    expect(merged[PROVIDER_TYPE.OPEN_ROUTER]).toHaveLength(1);
  });

  it("does not mutate the registry map", () => {
    const snapshot = JSON.stringify(registry);

    mergeProviderModels(registry, {
      [PROVIDER_TYPE.OPEN_ROUTER]: [model("~typesafe/jev-latest")],
    });

    expect(JSON.stringify(registry)).toBe(snapshot);
  });
});
