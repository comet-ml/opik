import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_TYPE } from "@/types/providers";
import { Span, SPAN_TYPE } from "@/types/traces";
import useLoadSpanIntoPlayground from "./useLoadSpanIntoPlayground";

const navigate = vi.fn();
const setPromptMap = vi.fn();

const mocks = vi.hoisted(() => ({
  providerKeys: [] as string[],
  isPendingModels: false,
}));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => navigate,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "workspace" })),
  useActiveProjectId: () => "project-1",
}));

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptMap: () => ({}),
  useSetPromptMap: () => setPromptMap,
  useSetDatasetType: () => vi.fn(),
  useSetExperimentName: () => vi.fn(),
}));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => ["gpt-4o-mini", vi.fn()],
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: {
      content: mocks.providerKeys.map((key) => ({ ui_composed_provider: key })),
    },
    isPending: false,
  }),
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: {
      [PROVIDER_TYPE.OPEN_AI]: [
        { id: "gpt-4o", label: "GPT-4o" },
        { id: "gpt-4o-mini", label: "GPT-4o mini" },
      ],
      [PROVIDER_TYPE.ANTHROPIC]: [
        { id: "claude-sonnet-4-6", label: "Claude Sonnet 4.6" },
      ],
      // The same bare model id under two providers.
      [PROVIDER_TYPE.GEMINI]: [
        { id: "gemini-2.5-pro", label: "Gemini 2.5 Pro" },
      ],
      [PROVIDER_TYPE.VERTEX_AI]: [
        {
          id: "gemini-2.5-pro",
          qualifiedName: "vertex_ai/gemini-2.5-pro",
          label: "Gemini 2.5 Pro",
        },
      ],
    },
    isPending: mocks.isPendingModels,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => ({}),
}));

const createSpan = (overrides: Partial<Span> = {}): Span =>
  ({
    id: "span-1",
    name: "chat_completion_create",
    type: SPAN_TYPE.llm,
    trace_id: "trace-1",
    parent_span_id: "",
    project_id: "project-1",
    model: "gpt-4o",
    provider: "openai",
    input: {
      messages: [
        { role: "system", content: "You are helpful." },
        { role: "user", content: "Hi" },
      ],
    },
    output: {},
    ...overrides,
  }) as Span;

const loadSpan = (span: Span): PlaygroundPromptType => {
  const { result } = renderHook(() => useLoadSpanIntoPlayground());
  result.current.loadSpan(span);

  expect(setPromptMap).toHaveBeenCalledTimes(1);
  const [promptIds, promptMap] = setPromptMap.mock.calls[0];
  expect(promptIds).toHaveLength(1);
  return promptMap[promptIds[0]];
};

describe("useLoadSpanIntoPlayground", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.providerKeys = [PROVIDER_TYPE.OPEN_AI];
    mocks.isPendingModels = false;
  });

  it("loads the span input messages as an unlinked chat prompt", () => {
    const prompt = loadSpan(createSpan());

    expect(
      prompt.messages.map(({ role, content }) => ({ role, content })),
    ).toEqual([
      { role: "system", content: "You are helpful." },
      { role: "user", content: "Hi" },
    ]);
    expect(prompt.loadedChatPromptId).toBeUndefined();
    expect(navigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/playground",
      params: { workspaceName: "workspace", projectId: "project-1" },
    });
  });

  it("selects the span model when a configured provider serves it", () => {
    const prompt = loadSpan(createSpan({ model: "gpt-4o" }));

    expect(prompt.model).toBe("gpt-4o");
    expect(prompt.provider).toBe(PROVIDER_TYPE.OPEN_AI);
  });

  it.each([
    ["its provider isn't configured", "claude-sonnet-4-6"],
    ["the model is unknown", "my-fine-tune"],
    ["the span has no model", undefined],
  ])("falls back to the last picked model when %s", (_, model) => {
    const prompt = loadSpan(createSpan({ model }));

    expect(prompt.model).toBe("gpt-4o-mini");
    expect(prompt.provider).toBe(PROVIDER_TYPE.OPEN_AI);
  });

  it("selects the configured provider for a model id two providers share", () => {
    mocks.providerKeys = [PROVIDER_TYPE.VERTEX_AI];

    const prompt = loadSpan(
      createSpan({ model: "gemini-2.5-pro", provider: "google_vertexai" }),
    );

    expect(prompt.model).toBe("vertex_ai/gemini-2.5-pro");
    expect(prompt.provider).toBe(PROVIDER_TYPE.VERTEX_AI);
  });

  it("is pending until the model catalog has loaded", () => {
    mocks.isPendingModels = true;

    const { result } = renderHook(() => useLoadSpanIntoPlayground());

    expect(result.current.isPending).toBe(true);
  });

  it("does nothing for a span without Playground messages", () => {
    const { result } = renderHook(() => useLoadSpanIntoPlayground());
    result.current.loadSpan(createSpan({ input: { query: "Hi" } }));

    expect(setPromptMap).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});
