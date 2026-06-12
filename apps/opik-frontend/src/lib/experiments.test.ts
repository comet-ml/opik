import { describe, expect, it } from "vitest";

import { formatPromptVersionLabel } from "./experiments";

describe("experiments utilities", () => {
  describe("formatPromptVersionLabel", () => {
    it("prefers the sequential version number", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: "v3",
          commit: "c96aa875",
        }),
      ).toBe("My Prompt (v3)");
    });

    it("falls back to the commit hash when no version number", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: undefined,
          commit: "c96aa875",
        }),
      ).toBe("My Prompt (c96aa875)");
    });

    it("omits the parenthetical when neither version nor commit is present", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: undefined,
          commit: "",
        }),
      ).toBe("My Prompt");
    });
  });
});
