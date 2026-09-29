import { describe, expect, it } from "vitest";
import { supportsAudioInput } from "@/lib/modelCapabilities";
import { CUSTOM_PROVIDER_MODEL_PREFIX } from "@/constants/providers";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

describe("supportsAudioInput", () => {
  describe("models flagged under another provider's name", () => {
    it.each([
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_5_TRANSCRIBE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH,
    ])("allows audio for %s", (model) => {
      expect(supportsAudioInput(model)).toBe(true);
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
