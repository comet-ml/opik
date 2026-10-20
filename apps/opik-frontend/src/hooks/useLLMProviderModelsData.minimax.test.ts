import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import YAML from "yaml";
import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import {
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import useLLMProviderModelsData from "./useLLMProviderModelsData";

dayjs.extend(utc);

const registry = vi.hoisted(() => ({ data: {} as LlmModelsByProvider }));
vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({ data: registry.data, isPending: false, isError: false }),
}));
vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => ({
    "custom-llm:local": [
      { value: "MiniMax-M2.7", label: "My saved MiniMax deployment" },
    ],
  }),
}));

const FLASH = "minimax/minimax-m3.1-flash:free";
const M3 = "minimax/minimax-m3";
const LEGACY = [
  "minimax/minimax-01",
  "minimax/minimax-m1",
  "minimax/minimax-m2",
  "minimax/minimax-m2.1",
  "minimax/minimax-m2.5",
  "minimax/minimax-m2.7",
  "minimax/minimax-m2.7-highspeed",
];
const modelEntry = (id: string) => ({
  id,
  label: id,
  structured_output: false,
  reasoning: false,
});

beforeEach(() => {
  registry.data = {
    [PROVIDER_TYPE.OPEN_ROUTER]: [
      "openai/gpt-4o",
      M3,
      ...LEGACY,
      "minimax/hailuo-02",
      FLASH,
      "minimax/speech-02-hd",
      "minimax/image-01",
      "anthropic/claude-sonnet-4",
    ].map(modelEntry),
    [PROVIDER_TYPE.OPEN_AI]: [modelEntry("gpt-4o")],
  };
});
afterEach(() => vi.restoreAllMocks());

describe("MiniMax ordinary text choices", () => {
  it("offers both current models from the bundled registry and retains legacy routing", () => {
    registry.data = YAML.parse(
      readFileSync(
        resolve(
          process.cwd(),
          "../opik-backend/src/main/resources/llm-models-default.yaml",
        ),
        "utf8",
      ),
    );
    const { result } = renderHook(() => useLLMProviderModelsData());
    expect(
      result.current.providerModels[PROVIDER_TYPE.OPEN_ROUTER]
        .filter((m) => m.value.startsWith("minimax/minimax-"))
        .map((m) => m.value),
    ).toEqual([FLASH, M3]);
    for (const id of LEGACY.filter((id) => !id.endsWith("-highspeed"))) {
      expect(
        result.current.calculateModelProvider(id as PROVIDER_MODEL_TYPE),
      ).toBe(PROVIDER_TYPE.OPEN_ROUTER);
    }
  });

  it("offers Flash then M3 while preserving other models and custom choices", () => {
    const { result } = renderHook(() => useLLMProviderModelsData());
    expect(
      result.current.providerModels[PROVIDER_TYPE.OPEN_ROUTER].map(
        (m) => m.value,
      ),
    ).toEqual([
      "openai/gpt-4o",
      FLASH,
      "minimax/hailuo-02",
      M3,
      "minimax/speech-02-hd",
      "minimax/image-01",
      "anthropic/claude-sonnet-4",
    ]);
    expect(result.current.providerModels["custom-llm:local"]).toEqual([
      { value: "MiniMax-M2.7", label: "My saved MiniMax deployment" },
    ]);
    expect(result.current.providerModels[PROVIDER_TYPE.OPEN_AI][0].value).toBe(
      "gpt-4o",
    );
    for (const id of LEGACY) {
      expect(
        result.current.calculateModelProvider(id as PROVIDER_MODEL_TYPE),
      ).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      expect(
        result.current.calculateDefaultModel(id as PROVIDER_MODEL_TYPE, [
          PROVIDER_TYPE.OPEN_ROUTER,
        ]),
      ).toBe(id);
    }
  });

  it("does not invent a Flash route when the registry has not synced it", () => {
    registry.data[PROVIDER_TYPE.OPEN_ROUTER] = [M3, ...LEGACY].map(modelEntry);
    const { result } = renderHook(() => useLLMProviderModelsData());
    expect(result.current.providerModels[PROVIDER_TYPE.OPEN_ROUTER]).toEqual([
      { value: M3, label: M3 },
    ]);
  });

  it.each([
    "default",
    M3,
    ...LEGACY,
    "MiniMax-M2.7",
    "my-custom-model",
    "gpt-4o",
  ])(
    "serializes %s unchanged through the real completion request path",
    async (selection) => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(new Response("data: [DONE]\n\n", { status: 200 }));
      const { result: catalog } = renderHook(() => useLLMProviderModelsData());
      const model = (
        selection === "default"
          ? catalog.current.providerModels[PROVIDER_TYPE.OPEN_ROUTER].find(
              (m) => m.value.startsWith("minimax/minimax-"),
            )!.value
          : selection
      ) as PROVIDER_MODEL_TYPE;
      const { result: run } = renderHook(() =>
        useCompletionProxyStreaming({ workspaceName: "offline-test" }),
      );
      const messages = [
        {
          role: LLM_MESSAGE_ROLE.user,
          content: [
            { type: "text" as const, text: "Describe this image" },
            {
              type: "image_url" as const,
              image_url: { url: "https://example.com/image.png" },
            },
          ],
        },
      ];
      await run.current({
        model,
        messages,
        configs: { maxCompletionTokens: 512 } as LLMPromptConfigsType,
        onAddChunk: vi.fn(),
        signal: new AbortController().signal,
      });
      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const [url, init] = fetchSpy.mock.calls[0];
      expect(url).toMatch(/\/v1\/private\/chat\/completions$/);
      expect(init?.method).toBe("POST");
      expect(JSON.parse(init?.body as string)).toMatchObject({
        model: selection === "default" ? FLASH : selection,
        messages,
        stream: true,
      });
    },
  );
});
