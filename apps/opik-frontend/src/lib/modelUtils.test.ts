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
    expect(supportsSamplingParams("never-seen-model" as PROVIDER_MODEL_TYPE))
      .toBe(true);
  });

  it("returns false when the BE flag is false", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
          { reasoning: false, structuredOutput: true, supportsSamplingParams: false },
        ],
      ]),
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7))
      .toBe(false);
  });

  it("returns true when the BE flag is true", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
          { reasoning: false, structuredOutput: true, supportsSamplingParams: true },
        ],
      ]),
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6))
      .toBe(true);
  });
});
