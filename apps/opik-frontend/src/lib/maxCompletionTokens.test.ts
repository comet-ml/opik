import { afterEach, describe, expect, it } from "vitest";
import {
  getMaxCompletionTokensRange,
  resolveMaxCompletionTokens,
  sanitizeConfigForRequest,
} from "@/lib/modelUtils";
import {
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";

describe("getMaxCompletionTokensRange", () => {
  it.each([
    [
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      undefined,
      1,
      16384,
    ],
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4, undefined, 1, 4096],
    [
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      "chat_completions_api",
      1,
      16384,
    ],
    [
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      "responses_api",
      16,
      16384,
    ],
    [PROVIDER_TYPE.OPEN_AI, "gpt-unlisted", undefined, 1, 128000],
    [
      PROVIDER_TYPE.ANTHROPIC,
      PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      undefined,
      1,
      64000,
    ],
    [
      PROVIDER_TYPE.ANTHROPIC,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      "responses_api",
      1,
      128000,
    ],
    [
      PROVIDER_TYPE.ANTHROPIC,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1,
      undefined,
      1,
      64000,
    ],
  ] as const)(
    "%s %s on %s allows %d to %d",
    (provider, model, openAiPipelineMode, min, max) => {
      expect(
        getMaxCompletionTokensRange(
          provider,
          model,
          openAiPipelineMode as OpenAiPipelineMode | undefined,
        ),
      ).toEqual({ min, max });
    },
  );
});

describe("resolveMaxCompletionTokens", () => {
  it.each([
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, 0, 4000],
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, 4000],
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, 0.5, 1],
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, 20.5, 20],
    [PROVIDER_TYPE.OPEN_AI, "computer-use-preview", 0, 1024],
    [PROVIDER_TYPE.OPEN_AI, "computer-use-preview", undefined, 1024],
    [PROVIDER_TYPE.OPEN_AI, "computer-use-preview", 2000, 1024],
  ] as const)(
    "%s %s turns a stored %s into %d",
    (provider, model, value, expected) => {
      expect(resolveMaxCompletionTokens(provider, model, value)).toBe(expected);
    },
  );
});

describe("sanitizeConfigForRequest max output tokens", () => {
  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each([
    [
      "above the model limit",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      100000,
      undefined,
      16384,
    ],
    [
      "0 on Chat Completions",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      0,
      undefined,
      4000,
    ],
    [
      "0 on the Responses API",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      0,
      "responses_api",
      4000,
    ],
    [
      "below 16 on the Responses API",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      5,
      "responses_api",
      16,
    ],
    [
      "within range",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      5,
      "chat_completions_api",
      5,
    ],
    ["with a fraction", PROVIDER_MODEL_TYPE.GPT_4O_MINI, 20.5, undefined, 20],
    ["0 on Claude", PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, 0, undefined, 4000],
    [
      "above Haiku 4.5's limit",
      PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      100000,
      undefined,
      64000,
    ],
    [
      "above 64000 on Opus 4.6",
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      100000,
      undefined,
      100000,
    ],
  ] as const)(
    "sends a stored value %s as %d",
    (_, model, maxCompletionTokens, openAiPipelineMode, expected) => {
      expect(
        sanitizeConfigForRequest(
          model,
          { maxCompletionTokens },
          openAiPipelineMode as OpenAiPipelineMode | undefined,
        ).maxCompletionTokens,
      ).toBe(expected);
    },
  );

  it.each([
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, undefined],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, null, null],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, NaN, 4000],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, Infinity, 16384],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, -Infinity, 1],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, undefined, 4000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, null, 4000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, NaN, 4000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, Infinity, 64000],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, -Infinity, 1],
  ] as const)(
    "on %s sends a stored %s as %s",
    (model, maxCompletionTokens, expected) => {
      expect(
        sanitizeConfigForRequest(model, { maxCompletionTokens })
          .maxCompletionTokens,
      ).toBe(expected);
    },
  );

  it("sends the default for a stored 0 only up to the model's limit", () => {
    const model = "computer-use-preview" as PROVIDER_MODEL_TYPE;
    const snapshot = getLatestProviderModelsSnapshot();
    setLatestProviderModelsSnapshot({
      ...snapshot,
      [PROVIDER_TYPE.OPEN_AI]: [
        ...(snapshot[PROVIDER_TYPE.OPEN_AI] ?? []),
        { value: model, label: model },
      ],
    });

    expect(
      sanitizeConfigForRequest(model, { maxCompletionTokens: 0 })
        .maxCompletionTokens,
    ).toBe(1024);
  });

  it("leaves a model the registry does not list alone", () => {
    const model = "claude-unlisted" as PROVIDER_MODEL_TYPE;

    for (const maxCompletionTokens of [0, 200000]) {
      expect(
        sanitizeConfigForRequest(model, { maxCompletionTokens })
          .maxCompletionTokens,
      ).toBe(maxCompletionTokens);
    }
  });

  describe.each([
    [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH],
    [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH],
    ["custom-llm/mock/mock-model"],
    ["custom-llm/b-ollama/qwen3:0.6b"],
    ["custom-llm/Bedrock us-east-2/openai.gpt-oss-20b-1:0"],
  ])("on %s, where 0 means no limit", (model) => {
    it.each([
      [0, undefined],
      [0.5, undefined],
      [1, 1],
      [20.5, 20],
      [100000, 100000],
    ])("sends a stored %s as %s", (maxCompletionTokens, expected) => {
      expect(
        sanitizeConfigForRequest(model as PROVIDER_MODEL_TYPE, {
          maxCompletionTokens,
        }),
      ).toEqual(
        expected === undefined ? {} : { maxCompletionTokens: expected },
      );
    });
  });

  it("leaves a custom model named like an OpenAI model to its own limits", () => {
    const customId = "custom-llm/my-gateway/gpt-4o-mini" as PROVIDER_MODEL_TYPE;
    setLatestProviderModelsSnapshot({
      ...getLatestProviderModelsSnapshot(),
      "custom-llm:my-gateway": [{ value: customId, label: "gpt-4o-mini" }],
    });

    expect(
      sanitizeConfigForRequest(customId, { maxCompletionTokens: 100000 })
        .maxCompletionTokens,
    ).toBe(100000);
  });

  it("does not add a limit OpenAI was not sent", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_4O_MINI, {}),
    ).not.toHaveProperty("maxCompletionTokens");
  });
});
