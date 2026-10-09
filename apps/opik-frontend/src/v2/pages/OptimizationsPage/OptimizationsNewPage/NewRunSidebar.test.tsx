import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { useFormContext } from "react-hook-form";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import { METRIC_TYPE, OPTIMIZER_TYPE } from "@/types/optimizations";
import type { LlmModelsByProvider } from "@/api/llm/useLlmModels";
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

const providerKeys = (providers: COMPOSED_PROVIDER_TYPE[]) => ({
  content: providers.map((provider) => ({
    id: provider,
    provider,
    ui_composed_provider: provider,
    configuration: {},
  })),
});

const CONFIGURED_PROVIDER_KEYS = providerKeys([
  PROVIDER_TYPE.OPEN_AI,
  PROVIDER_TYPE.OPEN_ROUTER,
]);

const NO_CUSTOM_MODELS: ProviderModelsMap = {};

const mocks = vi.hoisted(() => ({
  savedRun: null as unknown,
  isRegistryFetched: true,
  hasRegistryModels: true,
  realRegistryHook: false,
  providerKeysData: undefined as unknown,
  registryQuery: {} as Record<string, unknown>,
  openAICompatibleModels: {} as Record<string, unknown>,
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
  default: () => ({ data: mocks.providerKeysData }),
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => mocks.registryQuery,
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => mocks.openAICompatibleModels,
}));

// Most tests stub the hook. The registry-order tests need the real one: the bug they cover is in
// when it copies the registry into the store getProviderFromModel reads.
vi.mock("@/hooks/useLLMProviderModelsData", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/hooks/useLLMProviderModelsData")>();
  return {
    default: () =>
      mocks.realRegistryHook
        ? actual.default()
        : {
            providerModels: PICKER_MODELS,
            isFetched: mocks.isRegistryFetched,
            hasRegistryModels: mocks.hasRegistryModels,
            calculateModelProvider: (model: string) => {
              if (!mocks.hasRegistryModels) return "";
              const pickerProvider = Object.entries(PICKER_MODELS).find(
                ([, models]) => models.some((m) => m.value === model),
              )?.[0];
              return pickerProvider ?? HIDDEN_REGISTRY_MODELS[model] ?? "";
            },
          },
  };
});

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

const SAVED_PARAMETERS = {
  max_tokens: 300,
  custom_parameters: { reasoning: { effort: "low" } },
};

const savedRun = (
  model: string,
  optimizerModel?: string,
  parameters: Record<string, unknown> = SAVED_PARAMETERS,
) => ({
  id: RERUN_ID,
  name: "saved run",
  dataset_id: "dataset-1",
  studio_config: {
    dataset_name: "ds",
    prompt: { messages: [{ role: "user", content: "{{question}}" }] },
    llm_model: { model, parameters },
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

const readContent = () =>
  JSON.parse(screen.getByTestId("content").textContent ?? "{}");

const renderRerun = (model: string, optimizerModel?: string) => {
  mocks.savedRun = savedRun(model, optimizerModel);
  render(<NewRunSidebar open onClose={vi.fn()} rerunId={RERUN_ID} />);
  return readContent();
};

beforeEach(() => {
  mocks.realRegistryHook = false;
  mocks.providerKeysData = CONFIGURED_PROVIDER_KEYS;
  mocks.openAICompatibleModels = NO_CUSTOM_MODELS;
});

describe("NewRunSidebar — re-run of a model the picker no longer offers", () => {
  beforeEach(() => {
    mocks.isRegistryFetched = true;
    mocks.hasRegistryModels = true;
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
    mocks.hasRegistryModels = false;

    const content = renderRerun(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH);

    expect(content.modelName).toBe("");
    expect(content.savedModelReplacement).toBeNull();
  });
});

const registryModel = (id: string, label?: string) => ({
  id,
  ...(label && { label }),
  structuredOutput: false,
  reasoning: false,
});

const REGISTRY: LlmModelsByProvider = {
  [PROVIDER_TYPE.OPEN_AI]: [
    registryModel(PROVIDER_MODEL_TYPE.GPT_4O_MINI, "GPT 4o Mini"),
  ],
  [PROVIDER_TYPE.OPEN_ROUTER]: [
    registryModel(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI, "openai/gpt-4o-mini"),
    registryModel(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH),
    registryModel(PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU),
  ],
};

const REGISTRY_PENDING = {
  data: undefined,
  isPending: true,
  isFetched: false,
  isError: false,
  error: null,
};

const REGISTRY_FETCHED = {
  data: REGISTRY,
  isPending: false,
  isFetched: true,
  isError: false,
  error: null,
};

describe("NewRunSidebar — re-run with the real model registry hook", () => {
  beforeEach(() => {
    mocks.realRegistryHook = true;
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  // Neither id is in the bundled model list, so only the registry says they are OpenRouter's.
  it.each([
    {
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH,
      parameters: SAVED_PARAMETERS,
      expected: { maxTokens: 300, reasoningEffort: "low" },
    },
    {
      model: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU,
      parameters: { max_tokens: 300, custom_parameters: { top_k: 3 } },
      expected: { maxTokens: 300, topK: 3 },
    },
  ])(
    "seeds $model with its OpenRouter settings, whichever of keys and registry answers first",
    ({ model, parameters, expected }) => {
      mocks.savedRun = savedRun(model, undefined, parameters);
      const seeds = (["keys", "registry"] as const).map((first) => {
        mocks.providerKeysData =
          first === "keys" ? CONFIGURED_PROVIDER_KEYS : undefined;
        mocks.registryQuery =
          first === "registry" ? REGISTRY_FETCHED : REGISTRY_PENDING;
        const sidebar = () => (
          <NewRunSidebar open onClose={vi.fn()} rerunId={RERUN_ID} />
        );
        const { rerender, unmount } = render(sidebar());

        mocks.providerKeysData = CONFIGURED_PROVIDER_KEYS;
        mocks.registryQuery = REGISTRY_FETCHED;
        rerender(sidebar());
        const content = readContent();
        unmount();
        resetModelRegistryStoreForTesting();
        return content;
      });

      for (const content of seeds) {
        expect(content.modelName).toBe(model);
        expect(content.modelConfig).toMatchObject(expected);
        expect(content.modelConfig).not.toHaveProperty("maxCompletionTokens");
      }
      expect(seeds[0]).toEqual(seeds[1]);
    },
  );
});
