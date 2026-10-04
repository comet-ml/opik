import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_TYPE } from "@/types/providers";
import { Span, SPAN_TYPE } from "@/types/traces";
import useLoadSpanIntoPlayground from "./useLoadSpanIntoPlayground";

const navigate = vi.fn();
const setPromptMap = vi.fn();

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
    data: { content: [{ ui_composed_provider: PROVIDER_TYPE.OPEN_AI }] },
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
    },
    isPending: false,
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

  it("does nothing for a span without Playground messages", () => {
    const { result } = renderHook(() => useLoadSpanIntoPlayground());
    result.current.loadSpan(createSpan({ input: { query: "Hi" } }));

    expect(setPromptMap).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});
