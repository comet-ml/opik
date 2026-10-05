import React from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

import PlaygroundPage from "./PlaygroundPage";
import usePlaygroundStore from "@/store/PlaygroundStore";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const PROJECT_ID = "project-1";

const backend = vi.hoisted(() => ({
  providerKeys: { isPending: true } as {
    isPending: boolean;
    data?: { content: unknown[]; total: number };
  },
  registry: { isPending: true, isError: false } as {
    isPending: boolean;
    isError: boolean;
    data?: LlmModelsByProvider;
  },
  lastPickedModel: "",
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => backend.providerKeys,
}));
vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({ ...backend.registry, error: null }),
}));
vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [backend.lastPickedModel, vi.fn()],
}));
vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "workspace" }),
  useActiveProjectId: () => PROJECT_ID,
}));
vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: {} }),
}));
vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: null, setDatasetId: vi.fn() }),
}));
vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: undefined }),
}));
vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: () => ({ data: undefined }),
}));
vi.mock("@/api/datasets/useProjectDatasetsList", () => ({
  default: () => ({ data: undefined }),
}));
vi.mock(
  "@/v2/pages-shared/DatasetVersionSelectBox/useDatasetVersionSelect",
  () => ({ DEFAULT_LOADED_DATASETS: 1000 }),
);
vi.mock("@/hooks/useNavigationBlocker", () => ({
  default: () => ({ DialogComponent: null }),
}));
vi.mock("@/v2/pages/PlaygroundPage/useActionButtonActions", () => {
  const actions = {
    runAll: vi.fn(),
    stopAll: vi.fn(),
    runSingle: vi.fn(),
    stopSingle: vi.fn(),
  };
  return { default: () => actions };
});
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundScrollContainer", () => ({
  default: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
}));
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundHeader", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs",
  () => ({
    default: () => null,
  }),
);
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundAddVariant", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages-shared/llm/SetupProviderDialog/SetupProviderDialog",
  () => ({
    default: () => null,
  }),
);
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
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
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundRunButton", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundPrompts/usePromptBadgeColor",
  () => ({ default: () => ({ bg: "", text: "" }) }),
);
vi.mock("@/hooks/useLoadChatPrompt", () => ({
  default: () => ({
    loadedChatPromptRef: { current: null },
    hasUnsavedChatPromptChanges: false,
  }),
}));
vi.mock("@/hooks/usePromptVersionLabel", () => ({
  default: () => "",
}));

const OPEN_ROUTER = PROVIDER_TYPE.OPEN_ROUTER as COMPOSED_PROVIDER_TYPE;

const OPEN_ROUTER_KEYS = {
  content: [
    {
      id: "key-1",
      provider: PROVIDER_TYPE.OPEN_ROUTER,
      ui_composed_provider: OPEN_ROUTER,
    },
  ],
  total: 1,
};

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

const keysLoaded = () => {
  backend.providerKeys = { isPending: false, data: OPEN_ROUTER_KEYS };
};

const registryLoaded = () => {
  backend.registry = {
    isPending: false,
    isError: false,
    data: OPEN_ROUTER_REGISTRY,
  };
};

const renderPage = () => {
  const queryClient = new QueryClient();
  const page = () => (
    <QueryClientProvider client={queryClient}>
      <PlaygroundPage />
    </QueryClientProvider>
  );
  const { rerender } = render(page());

  return { rerender: () => rerender(page()) };
};

const storedPrompts = () => {
  const { promptIds, promptMap } = usePlaygroundStore.getState();
  return promptIds.map((id) => promptMap[id]);
};

beforeEach(() => {
  localStorage.clear();
  usePlaygroundStore.setState({
    lastActiveProjectId: null,
    promptIds: [],
    promptMap: {},
    outputMap: {},
  });
  backend.providerKeys = { isPending: true };
  backend.registry = { isPending: true, isError: false };
  backend.lastPickedModel = "";
});

describe("PlaygroundPage default prompt", () => {
  it("waits for the model registry when the provider keys land first, so the last picked model is honoured", () => {
    backend.lastPickedModel = PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5;
    const { rerender } = renderPage();

    keysLoaded();
    rerender();

    expect(storedPrompts()).toEqual([]);

    registryLoaded();
    rerender();

    const [prompt] = storedPrompts();
    expect(storedPrompts()).toHaveLength(1);
    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5);
    expect(prompt.provider).toBe(OPEN_ROUTER);
    expect(prompt.configs).toEqual(
      getDefaultConfigByProvider(
        OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
      ),
    );
  });

  it("still creates a default prompt with its provider when the model registry fails to load", () => {
    keysLoaded();
    backend.registry = { isPending: false, isError: true };

    renderPage();

    const [prompt] = storedPrompts();
    expect(storedPrompts()).toHaveLength(1);
    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O);
    expect(prompt.provider).toBe(OPEN_ROUTER);
    expect(prompt.configs).toEqual(
      getDefaultConfigByProvider(
        OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      ),
    );
  });

  it("heals a stored prompt that was created without a provider", () => {
    usePlaygroundStore.setState({
      lastActiveProjectId: PROJECT_ID,
      promptIds: ["p1"],
      promptMap: {
        p1: {
          id: "p1",
          name: "Prompt",
          messages: [],
          model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
          provider: "",
          configs: { throttling: 2 } as LLMPromptConfigsType,
        },
      },
    });
    keysLoaded();
    registryLoaded();

    renderPage();

    const [prompt] = storedPrompts();
    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O);
    expect(prompt.provider).toBe(OPEN_ROUTER);
    expect(prompt.configs).toEqual({
      ...getDefaultConfigByProvider(
        OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      ),
      throttling: 2,
    });
  });
});
