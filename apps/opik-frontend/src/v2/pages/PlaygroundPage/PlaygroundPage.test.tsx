import React from "react";
import { act, render, screen } from "@testing-library/react";
import {
  focusManager,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  MockInstance,
  vi,
} from "vitest";

import PlaygroundPage from "./PlaygroundPage";
import { TooltipProvider } from "@/ui/tooltip";
import usePlaygroundStore from "@/store/PlaygroundStore";
import { getDefaultConfigByProvider } from "@/lib/playground";
import api, {
  LLM_MODELS_REST_ENDPOINT,
  PROVIDER_KEYS_REST_ENDPOINT,
} from "@/api/api";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const PROJECT_ID = "project-1";

const lastPicked = vi.hoisted(() => ({ model: "" }));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [lastPicked.model, vi.fn()],
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
    default: ({ open }: { open: boolean }) =>
      open ? <div data-testid="setup-provider-dialog" /> : null,
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
  content: [{ id: "key-1", provider: PROVIDER_TYPE.OPEN_ROUTER }],
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

const ANTHROPIC_AND_OPENAI_KEYS = {
  content: [
    { id: "key-1", provider: PROVIDER_TYPE.ANTHROPIC },
    { id: "key-2", provider: PROVIDER_TYPE.OPEN_AI },
  ],
  total: 2,
};

const OPENAI_REGISTRY: LlmModelsByProvider = {
  [PROVIDER_TYPE.OPEN_AI]: [
    {
      id: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      label: "GPT 4o Mini",
      structuredOutput: true,
      reasoning: false,
    },
    {
      id: PROVIDER_MODEL_TYPE.GPT_4_1_MINI,
      label: "GPT 4.1 Mini",
      structuredOutput: true,
      reasoning: false,
    },
  ],
};

const storePrompts = (
  prompts: { model: PROVIDER_MODEL_TYPE; provider: COMPOSED_PROVIDER_TYPE }[],
) => {
  const promptMap = Object.fromEntries(
    prompts.map(({ model, provider }, index) => [
      `p${index + 1}`,
      {
        id: `p${index + 1}`,
        name: "Prompt",
        messages: [],
        model,
        provider,
        configs: getDefaultConfigByProvider(provider, model),
      },
    ]),
  );

  usePlaygroundStore.setState({
    lastActiveProjectId: PROJECT_ID,
    promptIds: Object.keys(promptMap),
    promptMap,
  });
};

type Reply = () => Promise<{ data: unknown }>;

const reply =
  (data: unknown): Reply =>
  () =>
    Promise.resolve({ data });

const fail: Reply = () => Promise.reject(new Error("Service Unavailable"));

const deferredReply = () => {
  let resolve: (data: unknown) => void = () => {};
  const response = new Promise<{ data: unknown }>((settle) => {
    resolve = (data) => settle({ data });
  });

  return { reply: () => response, resolve };
};

const backend: { providerKeys: Reply; registry: Reply } = {
  providerKeys: reply(OPEN_ROUTER_KEYS),
  registry: reply(OPEN_ROUTER_REGISTRY),
};

let apiGet: MockInstance;

const requestsTo = (url: string) =>
  apiGet.mock.calls.filter(([calledUrl]) => calledUrl === url).length;

// act() flushes React's renders only when it ends, so time moves in short steps: the page has to
// re-render between timers, as it does in a browser, for remounts and their refetches to happen.
const advance = async (ms = 0) => {
  let remaining = ms;
  do {
    const step = Math.min(remaining, 100);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(step);
    });
    remaining -= step;
  } while (remaining > 0);
};

const variantCards = () => screen.queryAllByTestId("playground-variant-card");

const renderPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <PlaygroundPage />
      </TooltipProvider>
    </QueryClientProvider>,
  );
};

const storedPrompts = () => {
  const { promptIds, promptMap } = usePlaygroundStore.getState();
  return promptIds.map((id) => promptMap[id]);
};

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
  usePlaygroundStore.setState({
    lastActiveProjectId: null,
    promptIds: [],
    promptMap: {},
    outputMap: {},
  });
  backend.providerKeys = reply(OPEN_ROUTER_KEYS);
  backend.registry = reply(OPEN_ROUTER_REGISTRY);
  lastPicked.model = "";
  apiGet = vi.spyOn(api, "get").mockImplementation(((url: string) => {
    if (url === PROVIDER_KEYS_REST_ENDPOINT) return backend.providerKeys();
    if (url === LLM_MODELS_REST_ENDPOINT) return backend.registry();
    return Promise.reject(new Error(`Unexpected GET ${url}`));
  }) as typeof api.get);
});

afterEach(() => {
  focusManager.setFocused(undefined);
  apiGet.mockRestore();
  vi.useRealTimers();
});

describe("PlaygroundPage default prompt", () => {
  it("waits for the model registry when the provider keys land first, so the last picked model is honoured", async () => {
    lastPicked.model = PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5;
    const registry = deferredReply();
    backend.registry = registry.reply;
    renderPage();

    await advance();

    expect(storedPrompts()).toEqual([]);
    expect(variantCards()).toHaveLength(0);

    registry.resolve(OPEN_ROUTER_REGISTRY);
    await advance();

    const [prompt] = storedPrompts();
    expect(storedPrompts()).toHaveLength(1);
    expect(variantCards()).toHaveLength(1);
    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5);
    expect(prompt.provider).toBe(OPEN_ROUTER);
    expect(prompt.configs).toEqual(
      getDefaultConfigByProvider(
        OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
      ),
    );
  });

  it("shows a default prompt with its provider once the model registry has failed, and stops asking for it", async () => {
    backend.registry = fail;
    renderPage();

    await advance(30_000);
    const registryRequests = requestsTo(LLM_MODELS_REST_ENDPOINT);

    for (let second = 0; second < 60; second++) {
      await advance(1_000);
      expect(variantCards()).toHaveLength(1);
    }
    expect(requestsTo(LLM_MODELS_REST_ENDPOINT)).toBe(registryRequests);

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

  it("shows the playground once the provider keys have failed, and stops asking for them", async () => {
    backend.providerKeys = fail;
    renderPage();

    await advance(1_000);
    const providerKeyRequests = requestsTo(PROVIDER_KEYS_REST_ENDPOINT);

    for (let second = 0; second < 10; second++) {
      await advance(1_000);
      expect(variantCards()).toHaveLength(1);
    }
    expect(requestsTo(PROVIDER_KEYS_REST_ENDPOINT)).toBe(providerKeyRequests);
  });

  it("gives the default prompt a model once the provider keys answer after failing", async () => {
    backend.providerKeys = fail;
    renderPage();
    await advance(1_000);

    expect(
      storedPrompts().map(({ model, provider }) => ({ model, provider })),
    ).toEqual([{ model: "", provider: "" }]);

    backend.providerKeys = reply(OPEN_ROUTER_KEYS);
    await act(async () => {
      focusManager.setFocused(true);
    });
    await advance();

    const [prompt] = storedPrompts();
    expect(prompt.model).toBe(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O);
    expect(prompt.provider).toBe(OPEN_ROUTER);
    expect(prompt.configs).toEqual(
      getDefaultConfigByProvider(
        OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      ),
    );
  });

  it("heals a stored prompt that was created without a provider", async () => {
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

    renderPage();
    await advance();

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

describe("PlaygroundPage stored models", () => {
  const OPEN_AI = PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE;
  const GEMINI = PROVIDER_TYPE.GEMINI as COMPOSED_PROVIDER_TYPE;

  it("keeps every stored model while the model registry has failed", async () => {
    backend.providerKeys = reply(ANTHROPIC_AND_OPENAI_KEYS);
    backend.registry = fail;
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GPT_4_1_MINI, provider: OPEN_AI },
    ]);

    renderPage();
    await advance(30_000);

    expect(variantCards()).toHaveLength(2);
    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.GPT_4_1_MINI,
    ]);
  });

  it("keeps every stored model while the provider keys have failed", async () => {
    backend.providerKeys = fail;
    backend.registry = reply(OPENAI_REGISTRY);
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GPT_4_1_MINI, provider: OPEN_AI },
    ]);
    const stored = storedPrompts();

    renderPage();
    await advance(30_000);

    expect(variantCards()).toHaveLength(2);
    expect(storedPrompts()).toEqual(stored);
  });

  it("checks the stored models once the provider keys answer after failing", async () => {
    backend.providerKeys = fail;
    backend.registry = reply(OPENAI_REGISTRY);
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO, provider: GEMINI },
    ]);

    renderPage();
    await advance(30_000);

    backend.providerKeys = reply(ANTHROPIC_AND_OPENAI_KEYS);
    await act(async () => {
      focusManager.setFocused(true);
    });
    await advance();

    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    ]);
  });

  it("keeps every stored model when the model registry answers with empty model lists", async () => {
    backend.providerKeys = reply(ANTHROPIC_AND_OPENAI_KEYS);
    backend.registry = reply({
      [PROVIDER_TYPE.OPEN_AI]: [],
      [PROVIDER_TYPE.ANTHROPIC]: [],
    });
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GPT_4_1_MINI, provider: OPEN_AI },
    ]);

    renderPage();
    await advance(30_000);

    expect(variantCards()).toHaveLength(2);
    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.GPT_4_1_MINI,
    ]);
  });

  it("checks the stored models once the model registry answers with models", async () => {
    backend.providerKeys = reply(ANTHROPIC_AND_OPENAI_KEYS);
    backend.registry = reply(OPENAI_REGISTRY);
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO, provider: GEMINI },
    ]);

    renderPage();
    await advance();

    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    ]);
  });

  it("checks the stored models once the model registry answers after failing", async () => {
    backend.providerKeys = reply(ANTHROPIC_AND_OPENAI_KEYS);
    backend.registry = fail;
    storePrompts([
      { model: PROVIDER_MODEL_TYPE.GPT_4O_MINI, provider: OPEN_AI },
      { model: PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO, provider: GEMINI },
    ]);

    renderPage();
    await advance(30_000);

    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO,
    ]);

    backend.registry = reply(OPENAI_REGISTRY);
    await act(async () => {
      focusManager.setFocused(true);
    });
    await advance();

    expect(storedPrompts().map(({ model }) => model)).toEqual([
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    ]);
  });
});

describe("PlaygroundPage setup provider dialog", () => {
  const setupDialog = () => screen.queryByTestId("setup-provider-dialog");

  it("opens when the workspace has no provider keys", async () => {
    backend.providerKeys = reply({ content: [], total: 0 });

    renderPage();
    await advance();

    expect(setupDialog()).not.toBeNull();
  });

  it("stays closed while the provider keys have failed", async () => {
    backend.providerKeys = fail;

    renderPage();
    await advance(30_000);

    expect(setupDialog()).toBeNull();
  });

  it("opens once the provider keys answer after failing with no keys", async () => {
    backend.providerKeys = fail;

    renderPage();
    await advance(30_000);
    expect(setupDialog()).toBeNull();

    backend.providerKeys = reply({ content: [], total: 0 });
    await act(async () => {
      focusManager.setFocused(true);
    });
    await advance();

    expect(setupDialog()).not.toBeNull();
  });
});
