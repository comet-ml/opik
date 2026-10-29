import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import useOpenAiPipelineMode from "./useOpenAiPipelineMode";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { ProviderObject, PROVIDER_TYPE } from "@/types/providers";

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: vi.fn(),
}));

const withKeys = (content?: Partial<ProviderObject>[]) =>
  vi.mocked(useProviderKeys).mockReturnValue({
    data: content ? { content, total: content.length } : undefined,
  } as ReturnType<typeof useProviderKeys>);

const modeFor = (content?: Partial<ProviderObject>[]) => {
  withKeys(content);
  return renderHook(() => useOpenAiPipelineMode("default")).result.current;
};

describe("useOpenAiPipelineMode", () => {
  it("reads the OpenAI key's pipeline mode", () => {
    expect(
      modeFor([
        {
          provider: PROVIDER_TYPE.OPEN_AI,
          configuration: { openai_pipeline_mode: "responses_api" },
        },
      ]),
    ).toBe("responses_api");
  });

  it("reads a mode written in upper case through the API, as the backend does", () => {
    expect(
      modeFor([
        {
          provider: PROVIDER_TYPE.OPEN_AI,
          configuration: {
            openai_pipeline_mode: "RESPONSES_API" as unknown as "responses_api",
          },
        },
      ]),
    ).toBe("responses_api");
  });

  it("knows no mode while the keys are still loading", () => {
    expect(modeFor(undefined)).toBeUndefined();
  });

  it.each<[string, Partial<ProviderObject>[]]>([
    ["the workspace has no OpenAI key", []],
    [
      "only another provider is set to the Responses API",
      [
        {
          provider: PROVIDER_TYPE.CUSTOM,
          configuration: { openai_pipeline_mode: "responses_api" },
        },
      ],
    ],
    [
      "the OpenAI key never chose a mode",
      [{ provider: PROVIDER_TYPE.OPEN_AI, configuration: {} }],
    ],
  ])("falls back to Chat Completions when %s", (_, content) => {
    expect(modeFor(content)).toBe("chat_completions_api");
  });
});
