import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import useMessageTextPrompt from "./useMessageTextPrompt";

vi.mock("@/api/prompts/usePromptById", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({ default: vi.fn() }));

const LATEST_VERSION = { id: "v2", template: "You are terse." };
const OLDER_VERSION = { id: "v1", template: "You are kind." };

const mockLoadedVersion = (version?: { id: string; template: string }) =>
  vi.mocked(usePromptVersionById).mockReturnValue({
    data: version,
  } as unknown as ReturnType<typeof usePromptVersionById>);

const messageLinkedTo = (
  content: string,
  promptVersionId?: string,
): LLMMessage => ({
  id: "message-1",
  role: LLM_MESSAGE_ROLE.system,
  content,
  promptId: "prompt-1",
  promptVersionId,
});

beforeEach(() => {
  vi.mocked(usePromptById).mockReturnValue({
    data: { id: "prompt-1", latest_version: LATEST_VERSION },
    error: null,
  } as unknown as ReturnType<typeof usePromptById>);
  mockLoadedVersion(undefined);
});

describe("useMessageTextPrompt", () => {
  it("is clean while the picked older version is still loading", () => {
    const { result } = renderHook(() =>
      useMessageTextPrompt(messageLinkedTo("You are kind.", "v1")),
    );

    expect(result.current.hasUnsavedChanges).toBe(false);
  });

  it("compares against the picked version once it loads", () => {
    mockLoadedVersion(OLDER_VERSION);

    const { result: unchanged } = renderHook(() =>
      useMessageTextPrompt(messageLinkedTo("You are kind.", "v1")),
    );
    const { result: edited } = renderHook(() =>
      useMessageTextPrompt(messageLinkedTo("You are kind!", "v1")),
    );

    expect(unchanged.current.hasUnsavedChanges).toBe(false);
    expect(edited.current.hasUnsavedChanges).toBe(true);
  });

  it("compares against the latest version when no version is picked", () => {
    const { result } = renderHook(() =>
      useMessageTextPrompt(messageLinkedTo("You are kind.")),
    );

    expect(result.current.hasUnsavedChanges).toBe(true);
  });
});
