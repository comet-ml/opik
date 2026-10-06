import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import useLoadChatPrompt, {
  UseLoadChatPromptOptions,
} from "./useLoadChatPrompt";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";

vi.mock("@/api/prompts/usePromptById", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({ default: vi.fn() }));

type TemplateMessage = { role: LLM_MESSAGE_ROLE; content: string };

const TEMPLATES: Record<string, TemplateMessage[]> = {
  v1: [
    { role: LLM_MESSAGE_ROLE.system, content: "You are kind." },
    { role: LLM_MESSAGE_ROLE.user, content: "Say hi to {{name}}" },
  ],
  v2: [
    { role: LLM_MESSAGE_ROLE.system, content: "You are terse." },
    { role: LLM_MESSAGE_ROLE.user, content: "Greet {{name}}" },
  ],
  v3: [{ role: LLM_MESSAGE_ROLE.user, content: "Another prompt" }],
};

const PROMPTS: Record<string, { name: string; latestVersionId: string }> = {
  greeter: { name: "Greeter", latestVersionId: "v2" },
  other: { name: "Other", latestVersionId: "v3" },
};

const PROMPT_BY_VERSION: Record<string, string> = {
  v1: "greeter",
  v2: "greeter",
  v3: "other",
};

const messagesOf = (versionId: string): LLMMessage[] =>
  TEMPLATES[versionId].map((message, index) => ({
    id: `${versionId}-${index}`,
    ...message,
  }));

const withUserEdit = (messages: LLMMessage[], suffix: string) =>
  messages.map((message) =>
    message.role === LLM_MESSAGE_ROLE.user
      ? { ...message, content: `${message.content} ${suffix}` }
      : message,
  );

const onMessagesLoaded = vi.fn();

const renderLoader = (options: Partial<UseLoadChatPromptOptions>) =>
  renderHook((props: UseLoadChatPromptOptions) => useLoadChatPrompt(props), {
    initialProps: {
      selectedChatPromptId: undefined,
      messages: [],
      onMessagesLoaded,
      ...options,
    },
  });

beforeEach(() => {
  onMessagesLoaded.mockClear();

  vi.mocked(usePromptById).mockImplementation(({ promptId }, options) => {
    const prompt = options?.enabled === false ? undefined : PROMPTS[promptId];
    return {
      data: prompt && {
        id: promptId,
        name: prompt.name,
        latest_version: { id: prompt.latestVersionId },
      },
      isSuccess: Boolean(prompt),
      error: null,
    } as unknown as ReturnType<typeof usePromptById>;
  });

  vi.mocked(usePromptVersionById).mockImplementation(
    ({ versionId }, options) => {
      const template =
        options?.enabled === false ? undefined : TEMPLATES[versionId];
      return {
        data: template && {
          id: versionId,
          prompt_id: PROMPT_BY_VERSION[versionId],
          template: JSON.stringify(template),
        },
        isSuccess: Boolean(template),
      } as unknown as ReturnType<typeof usePromptVersionById>;
    },
  );
});

const expectLoaded = (versionId: string, promptName: string) =>
  expect(onMessagesLoaded).toHaveBeenLastCalledWith(
    TEMPLATES[versionId].map((message) => expect.objectContaining(message)),
    promptName,
    versionId,
  );

describe("useLoadChatPrompt", () => {
  it("fills the messages from the library version the first time a prompt is loaded", () => {
    renderLoader({ selectedChatPromptId: "greeter" });

    expect(onMessagesLoaded).toHaveBeenCalledTimes(1);
    expectLoaded("v2", "Greeter");
  });

  it("keeps unsaved edits when the prompt mounts again after its version was applied", () => {
    const { result } = renderLoader({
      selectedChatPromptId: "greeter",
      selectedChatPromptVersionId: "v1",
      appliedChatPromptVersionId: "v1",
      messages: withUserEdit(messagesOf("v1"), "UNSAVED-EDIT"),
    });

    expect(onMessagesLoaded).not.toHaveBeenCalled();
    expect(result.current.hasUnsavedChatPromptChanges).toBe(true);
  });

  it("shows no unsaved changes after a remount when the messages still match the version", () => {
    const { result } = renderLoader({
      selectedChatPromptId: "greeter",
      selectedChatPromptVersionId: "v1",
      appliedChatPromptVersionId: "v1",
      messages: messagesOf("v1"),
    });

    expect(onMessagesLoaded).not.toHaveBeenCalled();
    expect(result.current.hasUnsavedChatPromptChanges).toBe(false);
  });

  it("applies the selected version when the one applied before was different", () => {
    renderLoader({
      selectedChatPromptId: "greeter",
      selectedChatPromptVersionId: "v2",
      appliedChatPromptVersionId: "v1",
      messages: messagesOf("v1"),
    });

    expectLoaded("v2", "Greeter");
  });

  it.each([
    {
      choice: "another version of the same prompt",
      promptId: "greeter",
      versionId: "v2",
      expectedVersionId: "v2",
      expectedName: "Greeter",
    },
    {
      choice: "another prompt",
      promptId: "other",
      versionId: undefined,
      expectedVersionId: "v3",
      expectedName: "Other",
    },
  ])(
    "replaces the edited messages when the user then picks $choice",
    ({ promptId, versionId, expectedVersionId, expectedName }) => {
      const edited = withUserEdit(messagesOf("v1"), "UNSAVED-EDIT");
      const { rerender } = renderLoader({
        selectedChatPromptId: "greeter",
        selectedChatPromptVersionId: "v1",
        appliedChatPromptVersionId: "v1",
        messages: edited,
      });

      rerender({
        selectedChatPromptId: undefined,
        messages: edited,
        onMessagesLoaded,
      });
      rerender({
        selectedChatPromptId: promptId,
        selectedChatPromptVersionId: versionId,
        messages: edited,
        onMessagesLoaded,
      });

      expect(onMessagesLoaded).toHaveBeenCalledTimes(1);
      expectLoaded(expectedVersionId, expectedName);
    },
  );

  it("still keeps the copied messages of a duplicated prompt", () => {
    renderLoader({
      selectedChatPromptId: "greeter",
      selectedChatPromptVersionId: "v1",
      messages: withUserEdit(messagesOf("v1"), "COPIED-EDIT"),
      skipInitialLoad: true,
    });

    expect(onMessagesLoaded).not.toHaveBeenCalled();
  });
});
