import { describe, it, expect } from "vitest";

import {
  groupMessageContentByRole,
  buildRoleDiffRows,
} from "./promptMessageDiff";

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
