import { afterEach, describe, expect, it } from "vitest";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
} from "@/lib/modelRegistryStore";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

describe("getDefaultConfigByProvider — Anthropic", () => {
  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("seeds temperature default when the model accepts sampling params", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
          {
            reasoning: false,
            structuredOutput: true,
            supportsSamplingParams: true,
          },
        ],
      ]),
    );

    const config = getDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBe(0);
  });

  it("omits temperature and topP when the model rejects sampling params", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
          {
            reasoning: false,
            structuredOutput: true,
            supportsSamplingParams: false,
          },
        ],
      ]),
    );

    const config = getDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBeUndefined();
    expect(config.topP).toBeUndefined();
    expect(config.maxCompletionTokens).toBe(4000);
  });
});
