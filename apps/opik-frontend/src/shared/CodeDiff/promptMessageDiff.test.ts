import { describe, it, expect } from "vitest";

import {
  groupMessageContentByRole,
  buildRoleDiffRows,
  buildDiffRows,
  buildPromptRows,
  getRoleLabel,
  promptToText,
  FALLBACK_ROLE,
} from "./promptMessageDiff";

describe("promptToText", () => {
  it("passes strings through unchanged", () => {
    expect(promptToText("You are a classifier.")).toBe("You are a classifier.");
  });

  it("returns an empty string for null and undefined", () => {
    expect(promptToText(null)).toBe("");
    expect(promptToText(undefined)).toBe("");
  });

  it("pretty-prints non-string prompts as JSON", () => {
    expect(promptToText({ role: "user" })).toBe(
      JSON.stringify({ role: "user" }, null, 2),
    );
  });
});

describe("getRoleLabel", () => {
  it("resolves known roles to their display names", () => {
    expect(getRoleLabel("system")).toBe("System");
    expect(getRoleLabel("assistant")).toBe("Assistant");
  });

  it("falls back to the raw role for unknown roles", () => {
    expect(getRoleLabel("critic")).toBe("critic");
  });
});

describe("groupMessageContentByRole", () => {
  it("groups messages by role in display order", () => {
    const prompt = [
      { role: "user", content: "hi" },
      { role: "system", content: "be helpful" },
    ];

    expect(groupMessageContentByRole(prompt)).toEqual([
      { role: "system", content: "be helpful" },
      { role: "user", content: "hi" },
    ]);
  });

  it("concatenates multiple messages of the same role", () => {
    const prompt = [
      { role: "system", content: "first" },
      { role: "system", content: "second" },
    ];

    expect(groupMessageContentByRole(prompt)).toEqual([
      { role: "system", content: "first\nsecond" },
    ]);
  });

  it("returns null for prompts that are not message arrays", () => {
    expect(groupMessageContentByRole("just a string")).toBeNull();
    expect(groupMessageContentByRole(null)).toBeNull();
    expect(groupMessageContentByRole({ foo: "bar" })).toBeNull();
  });

  it("emits a placeholder for media-only content instead of a blank card", () => {
    const prompt = [
      {
        role: "user",
        content: [
          {
            type: "image_url",
            image_url: { url: "https://example.com/x.png" },
          },
        ],
      },
    ];

    expect(groupMessageContentByRole(prompt)).toEqual([
      { role: "user", content: "[image_url]" },
    ]);
  });

  it("keeps text when a message mixes text and media", () => {
    const prompt = [
      {
        role: "user",
        content: [
          { type: "text", text: "describe this" },
          {
            type: "image_url",
            image_url: { url: "https://example.com/x.png" },
          },
        ],
      },
    ];

    expect(groupMessageContentByRole(prompt)).toEqual([
      { role: "user", content: "describe this" },
    ]);
  });
});

describe("buildRoleDiffRows", () => {
  it("pairs base and current content over the union of roles", () => {
    const baseline = [{ role: "system", content: "old system" }];
    const current = [
      { role: "system", content: "new system" },
      { role: "user", content: "added user" },
    ];

    expect(buildRoleDiffRows(baseline, current)).toEqual([
      {
        role: "system",
        baseContent: "old system",
        currentContent: "new system",
      },
      { role: "user", baseContent: "", currentContent: "added user" },
    ]);
  });

  it("returns empty base content for roles only present in current", () => {
    const baseline = [{ role: "system", content: "sys" }];
    const current = [{ role: "assistant", content: "assist" }];

    const rows = buildRoleDiffRows(baseline, current);

    expect(rows).toEqual([
      { role: "system", baseContent: "sys", currentContent: "" },
      { role: "assistant", baseContent: "", currentContent: "assist" },
    ]);
  });

  it("returns null when either side is not a message array", () => {
    const messages = [{ role: "system", content: "sys" }];

    expect(buildRoleDiffRows("string baseline", messages)).toBeNull();
    expect(buildRoleDiffRows(messages, "string current")).toBeNull();
  });
});

describe("buildDiffRows", () => {
  it("delegates to per-role rows for message-array prompts", () => {
    const target = [{ role: "system", content: "old" }];
    const current = [{ role: "system", content: "new" }];

    expect(buildDiffRows(target, current)).toEqual([
      { role: "system", baseContent: "old", currentContent: "new" },
    ]);
  });

  it("falls back to a single whole-text row when a side isn't a message array", () => {
    expect(buildDiffRows("before", "after")).toEqual([
      { role: FALLBACK_ROLE, baseContent: "before", currentContent: "after" },
    ]);
  });
});

describe("buildPromptRows", () => {
  it("delegates to per-role grouping for message-array prompts", () => {
    const prompt = [{ role: "user", content: "hi" }];

    expect(buildPromptRows(prompt)).toEqual([{ role: "user", content: "hi" }]);
  });

  it("falls back to a single whole-text row for non-message prompts", () => {
    expect(buildPromptRows("just a string")).toEqual([
      { role: FALLBACK_ROLE, content: "just a string" },
    ]);
  });

  it("returns an empty array for an empty prompt", () => {
    expect(buildPromptRows(null)).toEqual([]);
    expect(buildPromptRows("")).toEqual([]);
  });
});
