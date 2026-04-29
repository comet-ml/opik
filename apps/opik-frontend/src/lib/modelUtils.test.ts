import { afterEach, describe, expect, it } from "vitest";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
} from "@/lib/modelRegistryStore";
import { supportsSamplingParams } from "@/lib/modelUtils";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

describe("supportsSamplingParams", () => {
  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("returns true for an empty model selector", () => {
    expect(supportsSamplingParams("")).toBe(true);
    expect(supportsSamplingParams(undefined)).toBe(true);
  });

  it("returns true when no flag is registered for the model", () => {
    expect(
      supportsSamplingParams("never-seen-model" as PROVIDER_MODEL_TYPE),
    ).toBe(true);
  });

  it("returns false when the BE flag is false", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
          {
            reasoning: false,
            structuredOutput: true,
            supportsSamplingParams: false,
          },
        ],
      ]),
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6)).toBe(
      false,
    );
  });

  it("returns true when the BE flag is true", () => {
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
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6)).toBe(
      true,
    );
  });

  it("returns false from the hardcoded fallback even without BE flags", () => {
    // Opus 4.7 is in MODELS_WITHOUT_SAMPLING_PARAMS — the hardcoded list
    // is the source of truth during the hydration window or when the BE
    // response is missing the field.
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7)).toBe(
      false,
    );
  });

  it("hardcoded list overrides a stale BE flag of true for known-restricted models", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
          {
            reasoning: false,
            structuredOutput: true,
            supportsSamplingParams: true,
          },
        ],
      ]),
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7)).toBe(
      false,
    );
  });
});
