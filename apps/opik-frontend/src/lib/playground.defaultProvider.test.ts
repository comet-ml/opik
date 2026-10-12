import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  generateDefaultPrompt,
  getDefaultConfigByProvider,
  restoreMissingProviderAndConfigKeys,
} from "@/lib/playground";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { PROVIDERS } from "@/constants/providers";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { PlaygroundPromptType } from "@/types/playground";

const registry = vi.hoisted(() => ({
  data: undefined as LlmModelsByProvider | undefined,
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: registry.data,
    isPending: !registry.data,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => ({}),
}));

const OPEN_ROUTER = PROVIDER_TYPE.OPEN_ROUTER as COMPOSED_PROVIDER_TYPE;
const OPEN_AI_DEFAULT_MODEL = PROVIDERS[PROVIDER_TYPE.OPEN_AI]
  .defaultModel as PROVIDER_MODEL_TYPE;

const OPEN_ROUTER_REGISTRY: LlmModelsByProvider = {
  [PROVIDER_TYPE.OPEN_ROUTER]: [
    {
      id: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      label: "GPT-4o",
      structuredOutput: true,
      reasoning: false,
    },
    {
      id: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
      label: "Claude Sonnet 4.5",
      structuredOutput: true,
      reasoning: true,
    },
  ],
};

const renderResolvers = () => {
  const { result } = renderHook(() => useLLMProviderModelsData());

  return {
    providerResolver: result.current.calculateModelProvider,
    modelResolver: result.current.calculateDefaultModel,
    isDropdownModel: result.current.isDropdownModel,
  };
};

const storedPrompt = (
  configs: Record<string, unknown>,
  {
    provider = "",
    model = PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
  }: {
    provider?: COMPOSED_PROVIDER_TYPE | "";
    model?: PROVIDER_MODEL_TYPE;
  } = {},
) =>
  ({
    name: "Prompt",
    id: "p1",
    messages: [],
    model,
    provider,
    configs: configs as LLMPromptConfigsType,
  }) as PlaygroundPromptType;

beforeEach(() => {
  registry.data = undefined;
});

describe("generateDefaultPrompt", () => {
  it.each([
    PROVIDER_TYPE.OPEN_AI,
    PROVIDER_TYPE.ANTHROPIC,
    PROVIDER_TYPE.OPEN_ROUTER,
    PROVIDER_TYPE.GEMINI,
    PROVIDER_TYPE.VERTEX_AI,
  ])(
    "gives %s's default model its provider and defaults when the keys land before the model registry",
    (providerType) => {
      const provider = providerType as COMPOSED_PROVIDER_TYPE;
      const { defaultModel } = PROVIDERS[providerType];

      const prompt = generateDefaultPrompt({
        setupProviders: [provider],
        ...renderResolvers(),
      });

      expect(prompt.model).toBe(defaultModel);
      expect(prompt.provider).toBe(provider);
      expect(prompt.configs).toEqual(
        getDefaultConfigByProvider(provider, defaultModel),
      );
    },
  );

  it("honours the last picked model once the model registry has loaded", () => {
    registry.data = OPEN_ROUTER_REGISTRY;

    const prompt = generateDefaultPrompt({
      setupProviders: [OPEN_ROUTER],
      lastPickedModel: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
      ...renderResolvers(),
    });

    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5);
    expect(prompt.provider).toBe(OPEN_ROUTER);
  });

  it("starts on the provider default when the last picked model left the picker", () => {
    const OPEN_AI = PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE;
    registry.data = {
      [PROVIDER_TYPE.OPEN_AI]: [
        {
          id: OPEN_AI_DEFAULT_MODEL,
          label: "GPT 5.5",
          structuredOutput: true,
          reasoning: true,
        },
        {
          id: PROVIDER_MODEL_TYPE.GPT_LIVE_1,
          structuredOutput: false,
          reasoning: false,
        },
      ],
    };

    const prompt = generateDefaultPrompt({
      setupProviders: [OPEN_AI],
      lastPickedModel: PROVIDER_MODEL_TYPE.GPT_LIVE_1,
      ...renderResolvers(),
    });

    expect(prompt.model).toBe(OPEN_AI_DEFAULT_MODEL);
    expect(prompt.provider).toBe(OPEN_AI);
  });
});

describe("restoreMissingProviderAndConfigKeys", () => {
  const STORED_MODELS = [
    PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
    PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
  ];

  it.each(STORED_MODELS)(
    "writes the provider and all its default parameters into a %s prompt stored without them",
    (model) => {
      registry.data = OPEN_ROUTER_REGISTRY;

      const restored = restoreMissingProviderAndConfigKeys(
        storedPrompt({}, { model }),
        renderResolvers().providerResolver,
      );

      expect(restored.provider).toBe(OPEN_ROUTER);
      expect(restored.configs).toEqual(
        getDefaultConfigByProvider(OPEN_ROUTER, model),
      );
    },
  );

  it.each(STORED_MODELS)(
    "keeps the parameters the user set on a %s prompt",
    (model) => {
      registry.data = OPEN_ROUTER_REGISTRY;

      const restored = restoreMissingProviderAndConfigKeys(
        storedPrompt({ throttling: 2, maxConcurrentRequests: 1 }, { model }),
        renderResolvers().providerResolver,
      );

      expect(restored.configs).toEqual({
        ...getDefaultConfigByProvider(OPEN_ROUTER, model),
        throttling: 2,
        maxConcurrentRequests: 1,
      });
    },
  );

  it("leaves the prompt alone while its model resolves to no provider", () => {
    const prompt = storedPrompt({});

    expect(
      restoreMissingProviderAndConfigKeys(
        prompt,
        renderResolvers().providerResolver,
      ),
    ).toBe(prompt);
  });

  it("leaves a prompt that has a provider alone", () => {
    registry.data = OPEN_ROUTER_REGISTRY;
    const prompt = storedPrompt({ maxTokens: 100 }, { provider: OPEN_ROUTER });

    expect(
      restoreMissingProviderAndConfigKeys(
        prompt,
        renderResolvers().providerResolver,
      ),
    ).toBe(prompt);
  });
});
