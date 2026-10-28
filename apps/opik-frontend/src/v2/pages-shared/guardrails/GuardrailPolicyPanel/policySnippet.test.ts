import { describe, expect, it } from "vitest";

import { buildPolicySnippet } from "./policySnippet";

const namesLine = (snippet: string) =>
  snippet.split("\n").find((line) => line.includes("from_stored_policies"));

describe("buildPolicySnippet", () => {
  it("loads the policy by its trimmed name", () => {
    expect(namesLine(buildPolicySnippet("  support-bot  "))).toBe(
      'guardrail = Guardrail.from_stored_policies(names=["support-bot"])',
    );
  });

  it("falls back to a placeholder name while the name is empty", () => {
    expect(namesLine(buildPolicySnippet("   "))).toBe(
      'guardrail = Guardrail.from_stored_policies(names=["my-policy"])',
    );
  });

  it("escapes quotes, backslashes and control characters into a valid literal", () => {
    expect(namesLine(buildPolicySnippet('a "b" c\\d\te\nf\rg'))).toBe(
      'guardrail = Guardrail.from_stored_policies(names=["a \\"b\\" c\\\\d\\te\\nf\\rg"])',
    );
  });
});
