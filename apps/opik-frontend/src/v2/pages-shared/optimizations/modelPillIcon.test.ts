import { describe, it, expect } from "vitest";

import { getModelPillIcon } from "./modelPillIcon";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import ClaudeIcon from "@/icons/integrations/claude.svg?react";

describe("getModelPillIcon", () => {
  it("overrides Anthropic with the Claude logo (not the generic provider mark)", () => {
    expect(getModelPillIcon(PROVIDER_TYPE.ANTHROPIC)).toBe(ClaudeIcon);
    expect(getModelPillIcon(PROVIDER_TYPE.ANTHROPIC)).not.toBe(
      PROVIDERS[PROVIDER_TYPE.ANTHROPIC].icon,
    );
  });

  it("uses the provider's own mark when there is no override", () => {
    expect(getModelPillIcon(PROVIDER_TYPE.OPEN_AI)).toBe(
      PROVIDERS[PROVIDER_TYPE.OPEN_AI].icon,
    );
  });

  it("returns undefined for an unknown provider", () => {
    expect(getModelPillIcon(undefined)).toBeUndefined();
  });
});
