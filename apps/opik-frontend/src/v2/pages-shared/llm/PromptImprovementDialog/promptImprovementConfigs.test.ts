import { describe, expect, it } from "vitest";

import { withLowReasoning } from "./promptImprovementConfigs";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { sanitizeConfigForRequest } from "@/lib/modelUtils";
import { snakeCaseObj } from "@/lib/utils";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const sentForDefaultsWithLowReasoning = (
  provider: PROVIDER_TYPE,
  model: string,
) => {
  const defaults = getDefaultConfigByProvider(
    provider as COMPOSED_PROVIDER_TYPE,
    model as PROVIDER_MODEL_TYPE,
  );
  return snakeCaseObj(
    sanitizeConfigForRequest(
      model as PROVIDER_MODEL_TYPE,
      withLowReasoning(model as PROVIDER_MODEL_TYPE, defaults) as Record<
        string,
        unknown
      >,
    ),
  );
};

describe("withLowReasoning", () => {
  it.each([
    {
      provider: PROVIDER_TYPE.OPEN_AI,
      model: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      expected: { reasoning_effort: "low" },
    },
    {
      provider: PROVIDER_TYPE.OPEN_AI,
      model: PROVIDER_MODEL_TYPE.GPT_O3,
      expected: { reasoning_effort: "low" },
    },
    {
      provider: PROVIDER_TYPE.ANTHROPIC,
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      expected: { custom_parameters: { output_config: { effort: "low" } } },
    },
    {
      provider: PROVIDER_TYPE.GEMINI,
      model: PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      expected: { custom_parameters: { thinking: { level: "low" } } },
    },
    {
      provider: PROVIDER_TYPE.VERTEX_AI,
      model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW,
      expected: { custom_parameters: { thinking: { level: "low" } } },
    },
    {
      provider: PROVIDER_TYPE.GEMINI,
      model: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      expected: { custom_parameters: { thinking: { level: "low" } } },
    },
  ])("sends low reasoning for $model", ({ provider, model, expected }) => {
    expect(sentForDefaultsWithLowReasoning(provider, model)).toMatchObject({
      ...expected,
      max_completion_tokens: 4000,
    });
  });

  // Lowering must never switch thinking on: these models think less than "low" by default.
  it("keeps a Gemini model whose default thinks less than low", () => {
    expect(
      sentForDefaultsWithLowReasoning(
        PROVIDER_TYPE.GEMINI,
        PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      ),
    ).toMatchObject({ custom_parameters: { thinking: { level: "off" } } });
  });

  it.each([
    { provider: PROVIDER_TYPE.OPEN_AI, model: PROVIDER_MODEL_TYPE.GPT_4O_MINI },
    {
      provider: PROVIDER_TYPE.ANTHROPIC,
      model: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
    },
  ])("adds no effort to $model, which takes none", ({ provider, model }) => {
    const sent = sentForDefaultsWithLowReasoning(provider, model);

    expect(sent).not.toHaveProperty("reasoning_effort");
    expect(sent).not.toHaveProperty("custom_parameters");
  });

  it("leaves the caller's configs untouched", () => {
    const defaults = getDefaultConfigByProvider(
      PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.GPT_5_NANO,
    );
    withLowReasoning(PROVIDER_MODEL_TYPE.GPT_5_NANO, defaults);

    expect(defaults).toMatchObject({ reasoningEffort: "high" });
  });
});
