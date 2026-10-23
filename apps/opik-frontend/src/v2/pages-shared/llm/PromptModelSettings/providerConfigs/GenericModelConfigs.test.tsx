import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import GenericModelConfigs from "./GenericModelConfigs";
import {
  ANTHROPIC_PANEL,
  GEMINI_PANEL,
  GenericPanelPresentation,
  VERTEX_AI_PANEL,
} from "./genericPanels";
import ANTHROPIC_FIXTURE from "@/lib/anthropicCatalog.fixture.json";
import GEMINI_FIXTURE from "@/lib/geminiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import { getModelParams } from "@/lib/modelParams";
import {
  getGeminiOrVertexListedParams,
  sanitizeConfigForRequest,
} from "@/lib/modelUtils";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { ModelParamsByProvider } from "@/types/modelParams";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { ModelParams } from "@/types/modelParams";
import { getEffortLevels, resolveEffortLevel } from "@/lib/modelParams";
import { TooltipProvider } from "@/ui/tooltip";

const PRESENTATION: GenericPanelPresentation = {
  binding: {
    temperature: "temperature",
    top_p: "topP",
    max_output_tokens: "maxCompletionTokens",
    effort: "thinkingEffort",
  },
  storedNumbersOnly: false,
  reasoningNoun: "thinking",
  getEffortTitle: () => "Thinking effort",
  getEffortDescription: () => "How hard the model thinks.",
  effortPlaceholder: "Select thinking effort",
  getEffortLabel: (level) => level.toUpperCase(),
  getEffortChoice: (_model, params, configs) => ({
    levels: getEffortLevels(params.effort),
    value: resolveEffortLevel(params.effort, [configs.thinkingEffort]),
  }),
  getSamplingOffNote: () => undefined,
  throttlingDefault: 0,
  maxConcurrentRequestsDefault: 5,
};

const SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: true, min: 0, max: 1, default: 0 },
  top_p: { accepted: true, min: 0, max: 1, default: 1 },
  max_output_tokens: { accepted: true, min: 1, max: 8000, default: 4000 },
  effort: { accepted: true, levels: ["low", "high"], default: "high" },
  sampling_exclusive: true,
};

const STORED_NUMBERS_PRESENTATION: GenericPanelPresentation = {
  ...PRESENTATION,
  binding: {
    temperature: "temperature",
    top_p: "topP",
    max_output_tokens: "maxCompletionTokens",
    frequency_penalty: "frequencyPenalty",
    presence_penalty: "presencePenalty",
    effort: "reasoningEffort",
  },
  storedNumbersOnly: true,
  getEffortChoice: (_model, params, configs) => ({
    levels: getEffortLevels(params.effort),
    value: resolveEffortLevel(params.effort, [configs.reasoningEffort]),
  }),
};

const INDEPENDENT_SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: true, min: 0, max: 2, default: 0 },
  top_p: { accepted: true, min: 0, max: 1, default: 1 },
  max_output_tokens: { accepted: true, min: 16, max: 8000, default: 4000 },
  frequency_penalty: { accepted: true, min: -2, max: 2, default: 0 },
  presence_penalty: { accepted: true, min: -2, max: 2, default: 0 },
  effort: {
    accepted: true,
    levels: ["low", "medium", "high"],
    default: "medium",
  },
};

const NO_SAMPLING_MODEL: ModelParams = {
  ...SAMPLING_MODEL,
  temperature: { accepted: false },
  top_p: { accepted: false },
  effort: { accepted: false },
};

const renderPanel = (
  params: ModelParams,
  configs: Record<string, unknown>,
  unsupportedParams?: ReadonlySet<never> | typeof RULE_UNSUPPORTED_PARAMS,
  onChange = vi.fn(),
  presentation = PRESENTATION,
  model?: string,
) => {
  render(
    <TooltipProvider delayDuration={700}>
      <GenericModelConfigs
        model={model}
        params={params}
        presentation={presentation}
        configs={configs}
        onChange={onChange}
        unsupportedParams={unsupportedParams}
      />
    </TooltipProvider>,
  );
  return onChange;
};

describe("GenericModelConfigs", () => {
  it("draws the model's controls from its params", () => {
    renderPanel(SAMPLING_MODEL, {
      temperature: 0.7,
      maxCompletionTokens: 99999,
      thinkingEffort: "low",
    });

    expect(screen.getByText("Sampling")).toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("8000");
    expect(screen.getByTestId("throttling-input")).toBeInTheDocument();
    expect(
      screen.getByTestId("maxConcurrentRequests-input"),
    ).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent("LOW");
  });

  it("offers exactly the model's effort levels", () => {
    renderPanel(SAMPLING_MODEL, {});

    expect(screen.getByRole("combobox")).toHaveTextContent("HIGH");
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["LOW", "HIGH"]);
  });

  it("writes the picked level to the provider's config key", () => {
    const onChange = renderPanel(SAMPLING_MODEL, {});

    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("option", { name: "LOW" }), {
      key: "Enter",
    });

    expect(onChange).toHaveBeenCalledWith({ thinkingEffort: "low" });
  });

  it("switches the sampling half and clears the other", () => {
    const onChange = renderPanel(SAMPLING_MODEL, { temperature: 0.7 });

    fireEvent.click(screen.getByRole("radio", { name: "Top P" }));

    expect(onChange).toHaveBeenCalledWith({ topP: 1, temperature: undefined });
  });

  it("shows no sampling or effort control for a model that refuses them", () => {
    renderPanel(NO_SAMPLING_MODEL, { temperature: 0.7, thinkingEffort: "low" });

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toBeInTheDocument();
  });

  it("keeps to what an evaluator rule can store", () => {
    renderPanel(SAMPLING_MODEL, { temperature: 0.7 }, RULE_UNSUPPORTED_PARAMS);

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(
      screen.queryByTestId("maxCompletionTokens-input"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxConcurrentRequests-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("offers a slider only for a number the config holds, when the provider's panel works that way", () => {
    renderPanel(
      INDEPENDENT_SAMPLING_MODEL,
      { temperature: 0.5, frequencyPenalty: 0.2, reasoningEffort: "low" },
      undefined,
      vi.fn(),
      STORED_NUMBERS_PRESENTATION,
    );

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.5");
    expect(screen.getByTestId("frequencyPenalty-input")).toHaveValue("0.2");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("presencePenalty-input"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxCompletionTokens-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent("LOW");
  });

  it("offers no penalty the route refuses, even when the config holds one", () => {
    renderPanel(
      {
        ...INDEPENDENT_SAMPLING_MODEL,
        frequency_penalty: { accepted: false },
        presence_penalty: { accepted: false },
      },
      { frequencyPenalty: 0.2, presencePenalty: 0.1, maxCompletionTokens: 4 },
      undefined,
      vi.fn(),
      STORED_NUMBERS_PRESENTATION,
    );

    expect(
      screen.queryByTestId("frequencyPenalty-input"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("presencePenalty-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("16");
  });
});

describe("the catalog panel for a Claude model that thinks only when asked", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestModelParams(
      buildParamsIndex(
        ANTHROPIC_FIXTURE.models as unknown as LlmModelsByProvider,
      ),
    );
    setLatestProviderProfiles(
      ANTHROPIC_FIXTURE.profiles as unknown as ModelParamsByProvider,
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  const MODELS = [
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
  ];

  const defaults = (model: PROVIDER_MODEL_TYPE) =>
    getDefaultConfigByProvider(PROVIDER_TYPE.ANTHROPIC, model) as Record<
      string,
      unknown
    >;

  const renderCatalogPanel = (
    model: PROVIDER_MODEL_TYPE,
    configs: Record<string, unknown>,
  ) =>
    renderPanel(
      getModelParams(PROVIDER_TYPE.ANTHROPIC, model)!,
      configs,
      undefined,
      vi.fn(),
      ANTHROPIC_PANEL,
      model,
    );

  const sentEffort = (request: Record<string, unknown>) =>
    (
      (request.custom_parameters as Record<string, unknown> | undefined)
        ?.output_config as Record<string, unknown> | undefined
    )?.effort;

  it.each(MODELS)(
    "starts at Off with Off listed first, and sends no effort but the temperature, for %s",
    (model) => {
      const configs = defaults(model);
      renderCatalogPanel(model, configs);

      expect(screen.getByRole("combobox")).toHaveTextContent("Off");
      fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(["Off", "Low", "Medium", "High", "Max"]);
      expect(screen.getByTestId("temperature-input")).toBeInTheDocument();

      const request = sanitizeConfigForRequest(model, configs);
      expect(sentEffort(request)).toBeUndefined();
      expect(request.temperature).toBe(0);
    },
  );

  it.each(MODELS)(
    "hides the temperature while a level is picked, and Off brings it back, for %s",
    (model) => {
      const thinking = { ...defaults(model), thinkingEffort: "low" };
      renderCatalogPanel(model, thinking);

      expect(screen.getByRole("combobox")).toHaveTextContent("Low");
      expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
      expect(
        screen.getByText(/off while Claude can think/),
      ).toBeInTheDocument();
      const thinkingRequest = sanitizeConfigForRequest(model, thinking);
      expect(sentEffort(thinkingRequest)).toBe("low");
      expect(thinkingRequest.temperature).toBeUndefined();

      const offAgain = sanitizeConfigForRequest(model, {
        ...thinking,
        thinkingEffort: "off",
      });
      expect(sentEffort(offAgain)).toBeUndefined();
      expect(offAgain.temperature).toBe(0);
    },
  );
});

describe("the catalog panel for Gemini and Vertex AI", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestModelParams(
      buildParamsIndex(GEMINI_FIXTURE.models as unknown as LlmModelsByProvider),
    );
    setLatestProviderProfiles(
      GEMINI_FIXTURE.profiles as unknown as ModelParamsByProvider,
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  const renderGeminiPanel = (
    provider: PROVIDER_TYPE.GEMINI | PROVIDER_TYPE.VERTEX_AI,
    model: PROVIDER_MODEL_TYPE,
    configs: Record<string, unknown>,
    unsupportedParams?: typeof RULE_UNSUPPORTED_PARAMS,
  ) =>
    renderPanel(
      getGeminiOrVertexListedParams(model)!,
      configs,
      unsupportedParams,
      vi.fn(),
      provider === PROVIDER_TYPE.GEMINI ? GEMINI_PANEL : VERTEX_AI_PANEL,
      model,
    );

  const defaults = (provider: PROVIDER_TYPE, model: PROVIDER_MODEL_TYPE) =>
    getDefaultConfigByProvider(provider, model) as Record<string, unknown>;

  const levelsOffered = () => {
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    return screen.getAllByRole("option").map((option) => option.textContent);
  };

  const sentLevel = (request: Record<string, unknown>) =>
    (
      (request.custom_parameters as Record<string, unknown> | undefined)
        ?.thinking as Record<string, unknown> | undefined
    )?.level;

  it.each([
    [PROVIDER_TYPE.GEMINI, PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH],
    [PROVIDER_TYPE.VERTEX_AI, PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH],
  ] as const)(
    "%s %s: the curated levels, Medium first, and no sampling",
    (provider, model) => {
      const configs = defaults(provider, model);
      renderGeminiPanel(provider, model, configs);

      expect(screen.getByText("Thinking level")).toBeInTheDocument();
      expect(screen.getByRole("combobox")).toHaveTextContent("Medium");
      expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
      expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
      expect(screen.getByTestId("maxOutputTokens-input")).toHaveValue("4000");
      expect(levelsOffered()).toEqual(["Low", "Medium", "High"]);
      expect(sentLevel(sanitizeConfigForRequest(model, configs))).toBe(
        "medium",
      );
    },
  );

  it("Gemini 2.5 Flash: Auto first, Off kept, sampling shown, no level sent", () => {
    const model = PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH;
    const configs = defaults(PROVIDER_TYPE.GEMINI, model);
    renderGeminiPanel(PROVIDER_TYPE.GEMINI, model, configs);

    expect(screen.getByRole("combobox")).toHaveTextContent("Auto");
    expect(screen.getByTestId("temperature-input")).toHaveValue("0");
    expect(screen.getByTestId("topP-input")).toHaveValue("1");
    expect(levelsOffered()).toEqual(["Auto", "Off", "Low", "Medium", "High"]);
    const request = sanitizeConfigForRequest(model, configs);
    expect(request.custom_parameters).toBeUndefined();
    expect(request.temperature).toBe(0);
  });

  it("Vertex AI 3.5 Flash-Lite: the catalog's none is shown as Off and sends nothing", () => {
    const model = PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE;
    const configs = defaults(PROVIDER_TYPE.VERTEX_AI, model);
    renderGeminiPanel(PROVIDER_TYPE.VERTEX_AI, model, configs);

    expect(screen.getByRole("combobox")).toHaveTextContent("Off");
    expect(levelsOffered()).toEqual([
      "Off",
      "Minimal",
      "Low",
      "Medium",
      "High",
    ]);
    expect(
      sanitizeConfigForRequest(model, configs).custom_parameters,
    ).toBeUndefined();
  });

  it("shows a stored 0 as no limit and leaves it off the request", () => {
    const model = PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH;
    const configs = {
      ...defaults(PROVIDER_TYPE.GEMINI, model),
      maxCompletionTokens: 0,
    };
    renderGeminiPanel(PROVIDER_TYPE.GEMINI, model, configs);

    expect(screen.getByTestId("maxOutputTokens-input")).toHaveValue("0");
    expect(screen.getByText(/^No limit/)).toBeInTheDocument();
    expect(
      sanitizeConfigForRequest(model, configs).maxCompletionTokens,
    ).toBeUndefined();
  });

  it("keeps to what an evaluator rule can store", () => {
    const model = PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO;
    renderGeminiPanel(
      PROVIDER_TYPE.GEMINI,
      model,
      defaults(PROVIDER_TYPE.GEMINI, model),
      RULE_UNSUPPORTED_PARAMS,
    );

    expect(screen.getByTestId("temperature-input")).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent("Auto");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxOutputTokens-input"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
  });
});
