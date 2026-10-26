import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, renderHook, screen } from "@testing-library/react";
import groupBy from "lodash/groupBy";
import mapValues from "lodash/mapValues";

import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { PlaygroundPromptType } from "@/types/playground";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundPrompt from "./PlaygroundPrompt";

const PROVIDER_ROWS: Record<string, COMPOSED_PROVIDER_TYPE> = {
  "custom-llm/mock/mock-model": "custom-llm:mock",
  "custom-llm/mock/other-model": "custom-llm:mock",
  "custom-llm/gw/gw-model": "custom-llm:gw",
  "custom-llm/local/llama3.2:1b": "ollama:local",
  "custom-llm/local/qwen3:0.6b": "ollama:local",
  "custom-llm/us-east-2/openai.gpt-oss-20b-1:0": "bedrock:us-east-2",
  "custom-llm/us-east-2/openai.gpt-oss-120b-1:0": "bedrock:us-east-2",
  "gpt-4o-mini": "openai",
};

const REGISTRY: LlmModelsByProvider = {
  openai: [
    {
      id: "gpt-4o-mini",
      label: "GPT-4o mini",
      structured_output: true,
      reasoning: false,
    },
  ],
};

const PROVIDER_KEY_MODELS = mapValues(
  groupBy(
    Object.keys(PROVIDER_ROWS).filter(
      (model) => PROVIDER_ROWS[model] !== "openai",
    ),
    (model) => PROVIDER_ROWS[model],
  ),
  (models) => models.map((value) => ({ value, label: value })),
);

let prompt: PlaygroundPromptType;
const updatePrompt = vi.fn();

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptById: () => prompt,
  usePromptCount: () => 1,
  useDatasetVariables: () => [],
  useDatasetSampleData: () => null,
  useProviderValidationTrigger: () => 0,
  useUpdatePrompt: () => updatePrompt,
  useDeletePrompt: () => vi.fn(),
  useAddPrompt: () => vi.fn(),
  useUpdateOutput: () => vi.fn(),
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: REGISTRY,
    isPending: false,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => PROVIDER_KEY_MODELS,
}));

vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-id",
}));

// The prompt reads the selected dataset to know whether a run will be server-side, which decides
// the settings it hides. Stubbed so this file's react-query mock stays as narrow as it was: these
// tests are about switching models, and the real hook pulls a query chain behind it.
vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: null }),
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: {} }),
}));

vi.mock("@/api/prompts/useProjectPromptsList", () => ({
  default: () => ({ data: undefined }),
}));
vi.mock("@/api/prompts/usePromptById", () => ({ useFetchPrompt: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({
  useFetchPromptVersion: vi.fn(),
}));
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/useMessageTextPrompt", () => ({
  default: () => ({ hasUnsavedChanges: false }),
}));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [undefined, vi.fn()],
}));

vi.mock("@/hooks/useOpenAiPipelineMode", () => ({
  default: () => undefined,
}));

vi.mock("@/hooks/useLoadChatPrompt", () => ({
  default: () => ({ loadedChatPromptRef: { current: null } }),
}));

vi.mock("@/hooks/usePromptVersionLabel", () => ({
  default: () => undefined,
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundPrompts/usePromptBadgeColor",
  () => ({ default: () => ({ bg: "", text: "" }) }),
);

// The real picker reports the bare provider type, never the row's key.
vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: ({
    onChange,
  }: {
    onChange: (model: PROVIDER_MODEL_TYPE, provider: string) => void;
  }) => (
    <>
      {Object.entries(PROVIDER_ROWS).map(([model, row]) => (
        <button
          key={model}
          onClick={() =>
            onChange(model as PROVIDER_MODEL_TYPE, row.split(":")[0])
          }
        >
          {model}
        </button>
      ))}
    </>
  ),
}));

vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/PromptLibraryMenu/PromptLibraryMenu", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages-shared/llm/LoadedPromptDisplay/LoadedPromptDisplay",
  () => ({
    default: () => null,
  }),
);
vi.mock(
  "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog",
  () => ({ default: () => null }),
);

const USER_CONFIGS = {
  temperature: 0.2,
  maxCompletionTokens: 40,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0.4,
  custom_parameters: { seed: 3 },
  throttling: 0,
  maxConcurrentRequests: 5,
} as LLMPromptConfigsType;

const renderPrompt = (model: string) => {
  prompt = {
    id: "prompt-a",
    name: "Prompt",
    model: model as PROVIDER_MODEL_TYPE,
    provider: PROVIDER_ROWS[model],
    configs: USER_CONFIGS,
    messages: [],
  } as unknown as PlaygroundPromptType;

  render(
    <TooltipProvider>
      <PlaygroundPrompt
        workspaceName="default"
        index={0}
        promptId="prompt-a"
        providerKeys={Object.values(PROVIDER_ROWS)}
        hasLoadedProviderKeys={false}
        hasRegistryModels={false}
        providerResolver={
          renderHook(() => useLLMProviderModelsData()).result.current
            .calculateModelProvider
        }
        modelResolver={vi.fn()}
        isDropdownModel={vi.fn()}
      />
    </TooltipProvider>,
  );
};

const switchTo = (model: string) =>
  fireEvent.click(screen.getByRole("button", { name: model }));

const savedConfigs = () => updatePrompt.mock.lastCall?.[1].configs;

beforeEach(() => {
  updatePrompt.mockClear();
});

describe("PlaygroundPrompt model switch", () => {
  it.each([
    [
      "the same Custom LLM row",
      "custom-llm/mock/mock-model",
      "custom-llm/mock/other-model",
    ],
    [
      "the same Ollama row",
      "custom-llm/local/llama3.2:1b",
      "custom-llm/local/qwen3:0.6b",
    ],
    [
      "the same Bedrock row",
      "custom-llm/us-east-2/openai.gpt-oss-20b-1:0",
      "custom-llm/us-east-2/openai.gpt-oss-120b-1:0",
    ],
  ])("keeps the settings between two models of %s", (_label, from, to) => {
    renderPrompt(from);

    switchTo(to);

    expect(updatePrompt).toHaveBeenCalledWith(
      "prompt-a",
      expect.objectContaining({ model: to }),
    );
    expect(savedConfigs()).toEqual(USER_CONFIGS);
  });

  it.each([
    ["another Custom LLM row", "custom-llm/gw/gw-model"],
    ["another provider", "gpt-4o-mini"],
  ])("resets the settings on a switch to %s", (_label, to) => {
    renderPrompt("custom-llm/mock/mock-model");

    switchTo(to);

    expect(savedConfigs()).toEqual(
      getDefaultConfigByProvider(
        PROVIDER_ROWS[to].split(":")[0],
        to as PROVIDER_MODEL_TYPE,
      ),
    );
  });
});
