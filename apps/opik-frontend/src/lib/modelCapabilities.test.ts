import { describe, expect, it } from "vitest";
import {
  getMaxOutputTokens,
  getOpenRouterMaxOutputTokens,
  supportsAudioInput,
} from "@/lib/modelCapabilities";
import { CUSTOM_PROVIDER_MODEL_PREFIX } from "@/constants/providers";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

describe("supportsAudioInput", () => {
  describe("models flagged under another provider's name", () => {
    it.each([
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_5_TRANSCRIBE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH,
      "azure/gpt-4o-audio-preview-2024-12-17",
    ])("allows audio for %s", (model) => {
      expect(supportsAudioInput(model)).toBe(true);
    });

    it("allows audio for a name the data flags only under dated snapshots", () => {
      expect(
        supportsAudioInput(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_AUDIO_PREVIEW),
      ).toBe(true);
    });

    it("ignores a provider suffix after a colon", () => {
      expect(
        supportsAudioInput(
          `${PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH}:batch`,
        ),
      ).toBe(true);
    });
  });

  describe("models without audio support", () => {
    it.each([PROVIDER_MODEL_TYPE.GPT_4, PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5])(
      "blocks audio for %s",
      (model) => {
        expect(supportsAudioInput(model)).toBe(false);
      },
    );

    it.each([
      PROVIDER_MODEL_TYPE.GPT_4O,
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_EXTENDED,
    ])(
      "blocks audio for %s, which another provider's name explicitly denies",
      (model) => {
        expect(supportsAudioInput(model)).toBe(false);
      },
    );

    it("blocks audio for a model missing from the pricing data", () => {
      expect(supportsAudioInput("not-a-real-model")).toBe(false);
    });
  });

  it("allows audio for custom provider models", () => {
    expect(
      supportsAudioInput(`${CUSTOM_PROVIDER_MODEL_PREFIX}/my-local-model`),
    ).toBe(true);
  });

  it.each(["", null, undefined])("returns false for %p", (model) => {
    expect(supportsAudioInput(model)).toBe(false);
  });
});

describe("getMaxOutputTokens", () => {
  it.each([
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, 16384],
    [PROVIDER_MODEL_TYPE.GPT_4, 4096],
    [PROVIDER_MODEL_TYPE.GPT_O3, 100000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, 64000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_5_5, 128000],
    [PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5_5, 128000],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, 128000],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_6, 128000],
  ])("reads the limit of %s from the pricing data", (model, limit) => {
    expect(getMaxOutputTokens(model)).toBe(limit);
  });

  it.each([PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1, "", undefined])(
    "knows no limit for %s",
    (model) => {
      expect(getMaxOutputTokens(model)).toBeUndefined();
    },
  );
});

describe("getOpenRouterMaxOutputTokens", () => {
  it.each([
    {
      reads: "a model's own OpenRouter row",
      model: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_CONTENT_SAFETY,
      limit: 117964,
    },
    {
      reads: "a variant's own row over its base model's",
      model: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_CONTENT_SAFETY_FREE,
      limit: 8192,
    },
    {
      reads: "the base model's row for a variant without its own",
      model:
        PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_MISTRAL_24B_VENICE_EDITION_FREE,
      limit: 8192,
    },
    {
      reads: "no other provider's row",
      model: "openai/computer-use-preview",
      limit: undefined,
    },
    { reads: "nothing for an empty id", model: "", limit: undefined },
    { reads: "nothing for null", model: null, limit: undefined },
    { reads: "nothing for undefined", model: undefined, limit: undefined },
  ])("reads $reads", ({ model, limit }) => {
    expect(getOpenRouterMaxOutputTokens(model)).toBe(limit);
  });
});
