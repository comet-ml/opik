import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import useLoadChatPrompt from "./useLoadChatPrompt";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";

vi.mock("@/api/prompts/usePromptById", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({ default: vi.fn() }));

const TEMPLATES: Record<string, { role: LLM_MESSAGE_ROLE; content: string }[]> =
  {
    v1: [
      { role: LLM_MESSAGE_ROLE.system, content: "You are kind." },
      { role: LLM_MESSAGE_ROLE.user, content: "Say hi to {{name}}" },
    ],
    v2: [
      { role: LLM_MESSAGE_ROLE.system, content: "You are kind." },
      { role: LLM_MESSAGE_ROLE.user, content: "Say hi to {{name}} EDITED" },
    ],
  };

const messagesOf = (versionId: string): LLMMessage[] =>
  TEMPLATES[versionId].map((message, index) => ({
    id: `copy-${index}`,
    ...message,
  }));

const mockLibrary = ({
  latestVersionId,
  withLatestTemplate = true,
}: {
  latestVersionId: string;
  withLatestTemplate?: boolean;
}) => {
  vi.mocked(usePromptById).mockReturnValue({
    data: {
      id: "greeter",
      name: "Greeter",
      latest_version: {
        id: latestVersionId,
        ...(withLatestTemplate && {
          template: JSON.stringify(TEMPLATES[latestVersionId]),
        }),
      },
    },
    isSuccess: true,
    error: null,
  } as unknown as ReturnType<typeof usePromptById>);

  vi.mocked(usePromptVersionById).mockImplementation(
    ({ versionId }) =>
      ({
        data: {
          id: versionId,
          prompt_id: "greeter",
          template: JSON.stringify(TEMPLATES[versionId]),
        },
        isSuccess: true,
      }) as unknown as ReturnType<typeof usePromptVersionById>,
  );
};

const hasUnsavedChanges = (
  selectedChatPromptVersionId: string,
  messages: LLMMessage[],
) =>
  renderHook(() =>
    useLoadChatPrompt({
      selectedChatPromptId: "greeter",
      selectedChatPromptVersionId,
      messages,
      onMessagesLoaded: vi.fn(),
      skipInitialLoad: true,
    }),
  ).result.current.hasUnsavedChatPromptChanges;

beforeEach(() => {
  vi.mocked(usePromptById).mockReset();
  vi.mocked(usePromptVersionById).mockReset();
});

describe("useLoadChatPrompt unsaved changes", () => {
  it("treats messages saved as the latest version as saved, even when the variant still points at an older version", () => {
    mockLibrary({ latestVersionId: "v2" });

    expect(hasUnsavedChanges("v1", messagesOf("v2"))).toBe(false);
  });

  it("flags messages that match neither the selected nor the latest version", () => {
    mockLibrary({ latestVersionId: "v2" });
    const messages = messagesOf("v2").map((message) => ({
      ...message,
      content: `${message.content} MORE`,
    }));

    expect(hasUnsavedChanges("v1", messages)).toBe(true);
  });

  it("treats messages that match the selected older version as saved", () => {
    mockLibrary({ latestVersionId: "v2" });

    expect(hasUnsavedChanges("v1", messagesOf("v1"))).toBe(false);
  });

  it("flags edits against the selected version when the latest version has no template", () => {
    mockLibrary({ latestVersionId: "v2", withLatestTemplate: false });

    expect(hasUnsavedChanges("v1", messagesOf("v2"))).toBe(true);
  });
});
