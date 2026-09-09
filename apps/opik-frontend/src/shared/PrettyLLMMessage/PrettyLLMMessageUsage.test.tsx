import React from "react";
import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import PrettyLLMMessageUsage from "./PrettyLLMMessageUsage";

describe("PrettyLLMMessageUsage", () => {
  it.each([
    undefined,
    {},
    { completion_tokens: undefined },
    { completion_tokens: NaN },
  ])("does not render missing or invalid usage: %j", (usage) => {
    expect(renderToStaticMarkup(<PrettyLLMMessageUsage usage={usage} />)).toBe(
      "",
    );
  });

  it.each([
    ["cache_read_input_tokens", "Cache Read Input Tokens"],
    [
      "prompt_tokens_details.audio_tokens",
      "Prompt Tokens Details Audio Tokens",
    ],
    [
      "completion_tokens_details.audio_tokens",
      "Completion Tokens Details Audio Tokens",
    ],
    ["reasoning_tokens", "Reasoning Tokens"],
    ["provider_specific_tokens", "Provider Specific Tokens"],
  ])("renders reported %s even without completion tokens", (key, label) => {
    const usage = { completion_tokens: undefined, [key]: 7 };
    const html = renderToStaticMarkup(<PrettyLLMMessageUsage usage={usage} />);

    expect(html).toContain(label);
    expect(html).toContain(">7<");
    expect(html).not.toContain("Completion tokens");
  });

  it("preserves a reported zero", () => {
    const html = renderToStaticMarkup(
      <PrettyLLMMessageUsage usage={{ completion_tokens: 0 }} />,
    );
    expect(html).toContain("Completion tokens");
    expect(html).toContain(">0<");
  });
});
