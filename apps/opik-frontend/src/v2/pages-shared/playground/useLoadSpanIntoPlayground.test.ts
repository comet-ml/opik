import { renderHook } from "@testing-library/react";
import mustache from "mustache";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getPromptMustacheTags } from "@/lib/prompt";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_TYPE } from "@/types/providers";
import { Span, SPAN_TYPE } from "@/types/traces";
import useLoadSpanIntoPlayground from "./useLoadSpanIntoPlayground";

const navigate = vi.fn();
const setPromptMap = vi.fn();
const toast = vi.fn();

const mocks = vi.hoisted(() => ({
  providerKeys: [] as string[],
  isPendingModels: false,
}));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => navigate,
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
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
  useLastActiveProjectId: () => "project-1",
  useSetLastActiveProjectId: () => vi.fn(),
}));

vi.mock("@/v2/pages-shared/playground/useResetPlaygroundSession", () => ({
  default: () => vi.fn(),
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
        // In the catalog, but not offered in the model dropdown.
        { id: "gpt-4o-2024-08-06" },
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
          qualified_name: "vertex_ai/gemini-2.5-pro",
          label: "Gemini 2.5 Pro",
        },
      ],
    },
    isPending: mocks.isPendingModels,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/api/llm/useLlmModelProfiles", () => ({
  default: () => ({ data: undefined }),
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

  it("keeps logged braces as plain text", () => {
    const text = "Fill in {{name}} and {{#unbalanced}}";
    const prompt = loadSpan(
      createSpan({
        input: { messages: [{ role: "user", content: text }] },
      }),
    );

    const content = prompt.messages[0].content as string;

    expect(getPromptMustacheTags(content)).toEqual([]);
    expect(mustache.render(content, {})).toBe(text);
  });

  it("leaves messages without braces unchanged", () => {
    const prompt = loadSpan(createSpan());

    expect(prompt.messages[1].content).toBe("Hi");
  });

  it("tells the user when attachments weren't loaded", () => {
    const prompt = loadSpan(
      createSpan({
        input: {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "Describe this" },
                {
                  type: "image_url",
                  image_url: { url: "[input-attachment-1-1700000000.png]" },
                },
              ],
            },
          ],
        },
      }),
    );

    expect(prompt.messages[0].content).toBe("Describe this");
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast.mock.calls[0][0].description).toContain("attachments");
  });

  it("doesn't show the attachment message when nothing was skipped", () => {
    loadSpan(createSpan());

    expect(toast).not.toHaveBeenCalled();
  });

  it("selects the span model when a configured provider serves it", () => {
    const prompt = loadSpan(createSpan({ model: "gpt-4o" }));

    expect(prompt.model).toBe("gpt-4o");
    expect(prompt.provider).toBe(PROVIDER_TYPE.OPEN_AI);
  });

  it.each([
    ["its provider isn't configured", "claude-sonnet-4-6"],
    ["the model is unknown", "my-fine-tune"],
    ["the dropdown doesn't offer the model", "gpt-4o-2024-08-06"],
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

  it.each([
    ["google_vertexai", "vertex_ai/gemini-2.5-pro", PROVIDER_TYPE.VERTEX_AI],
    ["vertex-ai", "vertex_ai/gemini-2.5-pro", PROVIDER_TYPE.VERTEX_AI],
    ["google_ai", "gemini-2.5-pro", PROVIDER_TYPE.GEMINI],
    ["gemini", "gemini-2.5-pro", PROVIDER_TYPE.GEMINI],
  ])(
    "uses the span's provider for a shared model id: %s",
    (provider, expectedModel, expectedProvider) => {
      for (const providerKeys of [
        [PROVIDER_TYPE.GEMINI, PROVIDER_TYPE.VERTEX_AI],
        [PROVIDER_TYPE.VERTEX_AI, PROVIDER_TYPE.GEMINI],
      ]) {
        setPromptMap.mockClear();
        mocks.providerKeys = providerKeys;

        const prompt = loadSpan(
          createSpan({ model: "gemini-2.5-pro", provider }),
        );

        expect(prompt.model).toBe(expectedModel);
        expect(prompt.provider).toBe(expectedProvider);
      }
    },
  );

  it("falls back to another provider that offers the model", () => {
    mocks.providerKeys = [PROVIDER_TYPE.GEMINI];

    const prompt = loadSpan(
      createSpan({ model: "gemini-2.5-pro", provider: "google_vertexai" }),
    );

    expect(prompt.model).toBe("gemini-2.5-pro");
    expect(prompt.provider).toBe(PROVIDER_TYPE.GEMINI);
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
