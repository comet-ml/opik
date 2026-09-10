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
    ["cache_read_input_tokens", "Cache read tokens"],
    ["prompt_tokens_details.audio_tokens", "Input audio tokens"],
    ["completion_tokens_details.audio_tokens", "Output audio tokens"],
    ["reasoning_tokens", "Reasoning tokens"],
    ["provider_specific_tokens", "Provider specific tokens"],
  ])("renders reported %s even without completion tokens", (key, label) => {
    const usage = { completion_tokens: undefined, [key]: 7 };
    const html = renderToStaticMarkup(<PrettyLLMMessageUsage usage={usage} />);

    expect(html).toContain(label);
    expect(html).toContain(">7<");
    expect(html).not.toContain("Output tokens");
  });

  it("preserves a reported zero", () => {
    const html = renderToStaticMarkup(
      <PrettyLLMMessageUsage usage={{ completion_tokens: 0 }} />,
    );
    expect(html).toContain("Output tokens");
    expect(html).toContain(">0<");
  });
});

it("renders display metrics without internal duplicates", () => {
  const html = renderToStaticMarkup(
    <PrettyLLMMessageUsage
      usage={{
        prompt_tokens: 10,
        completion_tokens: 5,
        total_tokens: 15,
        "original_usage.input_tokens": 8,
        "original_usage.prompt_tokens": 10,
        "original_usage.cache_read_input_tokens": 2,
        invalid: NaN,
      }}
    />,
  );
  for (const [label, value] of [
    ["Input tokens", 10],
    ["Output tokens", 5],
    ["Total tokens", 15],
    ["Cache read tokens", 2],
  ]) {
    expect(html).toContain(`${label}</span><span>${value}</span>`);
  }
  expect(html).not.toContain("Original");
  expect(html).not.toContain("Invalid");
});
