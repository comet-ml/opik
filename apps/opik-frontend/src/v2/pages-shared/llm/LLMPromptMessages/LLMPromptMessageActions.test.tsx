import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import LLMPromptMessageActions from "./LLMPromptMessageActions";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { TooltipProvider } from "@/ui/tooltip";

vi.mock("@/api/prompts/usePromptById", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({ default: vi.fn() }));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: {
      canViewPrompts: true,
      canCreatePrompts: true,
      canEditPrompts: true,
    },
  }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-1",
}));

vi.mock("@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox", () => ({
  default: ({ hasUnsavedChanges }: { hasUnsavedChanges?: boolean }) => (
    <div
      data-testid="prompts-select-box"
      data-unsaved={String(Boolean(hasUnsavedChanges))}
    />
  ),
}));

vi.mock(
  "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog",
  () => ({ default: () => null }),
);

vi.mock("@/shared/ConfirmDialog/ConfirmDialog", () => ({
  default: () => null,
}));

const VERSIONS: Record<string, string> = {
  v1: "Say hi to {{name}}",
  v2: "Say hi to {{name}} EDITED",
};

const mockLibrary = (latestVersionId: string) => {
  vi.mocked(usePromptById).mockReturnValue({
    data: {
      id: "greeter",
      name: "Greeter",
      latest_version: {
        id: latestVersionId,
        template: VERSIONS[latestVersionId],
      },
    },
    error: null,
  } as unknown as ReturnType<typeof usePromptById>);

  vi.mocked(usePromptVersionById).mockImplementation(
    ({ versionId }) =>
      ({
        data: { id: versionId, template: VERSIONS[versionId] },
      }) as unknown as ReturnType<typeof usePromptVersionById>,
  );
};

const renderActions = (promptVersionId: string, content: string) => {
  const message: LLMMessage = {
    id: "message-1",
    role: LLM_MESSAGE_ROLE.user,
    content,
    promptId: "greeter",
    promptVersionId,
  };

  render(
    <TooltipProvider>
      <LLMPromptMessageActions
        message={message}
        onChangeMessage={vi.fn()}
        setIsLoading={vi.fn()}
        setIsHoldActionsVisible={vi.fn()}
      />
    </TooltipProvider>,
  );

  return screen.getByTestId("prompts-select-box").dataset.unsaved;
};

beforeEach(() => {
  vi.mocked(usePromptById).mockReset();
  vi.mocked(usePromptVersionById).mockReset();
});

describe("LLMPromptMessageActions unsaved changes", () => {
  it("treats a message saved as the latest version as saved, even when it still points at an older version", () => {
    mockLibrary("v2");

    expect(renderActions("v1", VERSIONS.v2)).toBe("false");
  });

  it("flags a message that matches neither the selected nor the latest version", () => {
    mockLibrary("v2");

    expect(renderActions("v1", `${VERSIONS.v2} MORE`)).toBe("true");
  });

  it("treats a message that matches the selected older version as saved", () => {
    mockLibrary("v2");

    expect(renderActions("v1", VERSIONS.v1)).toBe("false");
  });
});
