import { describe, expect, it } from "vitest";

import {
  canShowLLMMessages,
  resolveLLMMessageFormatHint,
} from "./messageRendering";

const OPENAI_INPUT = {
  messages: [{ role: "user", content: "Hello" }],
};

describe("resolveLLMMessageFormatHint", () => {
  it("keeps an explicit span provider", () => {
    expect(resolveLLMMessageFormatHint("langchain", ["openai"])).toBe(
      "langchain",
    );
  });

  it.each(["unsupported", "__proto__", "constructor"])(
    "falls back for an unregistered span provider: %s",
    (provider) => {
      expect(resolveLLMMessageFormatHint(provider, ["openai"])).toBeUndefined();
    },
  );

  it("uses a single registered trace provider", () => {
    expect(resolveLLMMessageFormatHint(undefined, ["openai"])).toBe("openai");
  });

  it.each([
    [undefined, undefined],
    [[], undefined],
    [["openai", "anthropic"], undefined],
    [["anthropic"], undefined],
  ])(
    "falls back for ambiguous or unsupported trace providers",
    (providers, expected) => {
      expect(resolveLLMMessageFormatHint(undefined, providers)).toBe(expected);
    },
  );
});

describe("canShowLLMMessages", () => {
  it("allows one supported field when the other is empty", () => {
    expect(canShowLLMMessages(OPENAI_INPUT, {}, "openai")).toBe(true);
  });

  it("rejects mixed supported and invalid fields", () => {
    expect(
      canShowLLMMessages(OPENAI_INPUT, { unexpected: true }, "openai"),
    ).toBe(false);
  });
});
