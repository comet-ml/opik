import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { useFormContext } from "react-hook-form";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import { METRIC_TYPE, OPTIMIZER_TYPE } from "@/types/optimizations";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";
import NewRunSidebar from "./NewRunSidebar";

const RERUN_ID = "rerun-1";

const PICKER_MODELS: ProviderModelsMap = {
  [PROVIDER_TYPE.OPEN_AI]: [
    { value: PROVIDER_MODEL_TYPE.GPT_4O_MINI, label: "GPT 4o Mini" },
  ],
  [PROVIDER_TYPE.OPEN_ROUTER]: [
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      label: "openai/gpt-4o-mini",
    },
  ],
};

const HIDDEN_REGISTRY_MODELS: Record<string, PROVIDER_TYPE> = {
  [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH]: PROVIDER_TYPE.OPEN_ROUTER,
  [PROVIDER_MODEL_TYPE.GPT_LIVE_1]: PROVIDER_TYPE.OPEN_AI,
  [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5]: PROVIDER_TYPE.ANTHROPIC,
};

const CONFIGURED_PROVIDERS = [PROVIDER_TYPE.OPEN_AI, PROVIDER_TYPE.OPEN_ROUTER];

const mocks = vi.hoisted(() => ({
  savedRun: null as unknown,
  isRegistryFetched: true,
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "default" }),
  useActiveProjectId: () => "project-1",
}));

vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {},
  trackEvent: vi.fn(),
}));

vi.mock("@/api/datasets/useGetOrCreateDemoDataset", () => ({
  default: () => ({ getOrCreateDataset: vi.fn() }),
}));

vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/api/optimizations/useOptimizationById", () => ({
  default: () => ({ data: mocks.savedRun, isFetching: false }),
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: {
      content: CONFIGURED_PROVIDERS.map((provider) => ({
        id: provider,
        provider,
        ui_composed_provider: provider,
        configuration: {},
      })),
    },
  }),
}));

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    providerModels: PICKER_MODELS,
    isFetched: mocks.isRegistryFetched,
    calculateModelProvider: (model: string) => {
      if (!mocks.isRegistryFetched) return "";
      const pickerProvider = Object.entries(PICKER_MODELS).find(([, models]) =>
        models.some((m) => m.value === model),
      )?.[0];
      return pickerProvider ?? HIDDEN_REGISTRY_MODELS[model] ?? "";
    },
  }),
}));

vi.mock("@/shared/ResizableSidePanel/ResizableSidePanel", () => ({
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock("./OptimizationsNewPageContent", () => ({
  default: function ContentProbe(props: {
    availableModels: string[];
    savedModelReplacement?: unknown;
  }) {
    const form = useFormContext();
    return (
      <pre data-testid="content">
        {JSON.stringify({
          availableModels: props.availableModels,
          savedModelReplacement: props.savedModelReplacement ?? null,
          modelName: form.watch("modelName"),
          modelConfig: form.watch("modelConfig"),
          optimizerModel: form.watch("optimizerParams")?.model ?? null,
        })}
      </pre>
    );
  },
}));

const savedRun = (model: string, optimizerModel?: string) => ({
  id: RERUN_ID,
  name: "saved run",
  dataset_id: "dataset-1",
  studio_config: {
    dataset_name: "ds",
    prompt: { messages: [{ role: "user", content: "{{question}}" }] },
    llm_model: {
      model,
      parameters: {
        max_tokens: 300,
        custom_parameters: { reasoning: { effort: "low" } },
      },
    },
    optimizer: {
      type: OPTIMIZER_TYPE.GEPA,
      parameters: {
        seed: 42,
        ...(optimizerModel && { model: optimizerModel }),
      },
    },
    evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS, parameters: {} }] },
  },
});

const renderRerun = (model: string, optimizerModel?: string) => {
  mocks.savedRun = savedRun(model, optimizerModel);
  render(<NewRunSidebar open onClose={vi.fn()} rerunId={RERUN_ID} />);
  return JSON.parse(screen.getByTestId("content").textContent ?? "{}");
};

describe("NewRunSidebar — re-run of a model the picker no longer offers", () => {
  beforeEach(() => {
    mocks.isRegistryFetched = true;
    const snapshot = getLatestProviderModelsSnapshot();
    setLatestProviderModelsSnapshot({
      ...snapshot,
      [PROVIDER_TYPE.OPEN_ROUTER]: [
        ...(snapshot[PROVIDER_TYPE.OPEN_ROUTER] ?? []),
        {
          value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH,
          label: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH,
        },
      ],
    });
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each([
    PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH,
    PROVIDER_MODEL_TYPE.GPT_LIVE_1,
  ])(
    "keeps %s, which the registry knows and a configured provider serves",
    (model) => {
      const content = renderRerun(model);

      expect(content.modelName).toBe(model);
      expect(content.availableModels).toContain(model);
      expect(content.savedModelReplacement).toBeNull();
    },
  );

  it("keeps a kept OpenRouter model's saved settings", () => {
    const content = renderRerun(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH);

    expect(content.modelConfig).toMatchObject({
      maxTokens: 300,
      custom_parameters: { reasoning: { effort: "low" } },
    });
  });

  it.each([
    { model: "openai/r3c-retired-model", why: "the registry does not know" },
    {
      model: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      why: "no configured provider serves",
    },
  ])("replaces $model, which $why, and says so", ({ model }) => {
    const content = renderRerun(model);

    expect(content.modelName).toBe(PROVIDER_MODEL_TYPE.GPT_4O_MINI);
    expect(content.availableModels).not.toContain(model);
    expect(content.savedModelReplacement).toEqual({
      savedModel: model,
      replacementModel: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      replacementLabel: "GPT 4o Mini",
    });
    expect(content.modelConfig).not.toHaveProperty("custom_parameters");
    expect(content.modelConfig).not.toHaveProperty("maxTokens");
  });

  it("keeps the saved model in the picker unchanged", () => {
    const content = renderRerun(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI);

    expect(content.modelName).toBe(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI);
    expect(content.savedModelReplacement).toBeNull();
    expect(content.modelConfig).toMatchObject({ maxTokens: 300 });
  });

  it("keeps a hidden algorithm model a configured provider serves", () => {
    const content = renderRerun(
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.GPT_LIVE_1,
    );

    expect(content.optimizerModel).toBe(PROVIDER_MODEL_TYPE.GPT_LIVE_1);
  });

  it("waits for the registry before deciding the saved model is gone", () => {
    mocks.isRegistryFetched = false;

    const content = renderRerun(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH);

    expect(content.modelName).toBe("");
    expect(content.savedModelReplacement).toBeNull();
  });
});
