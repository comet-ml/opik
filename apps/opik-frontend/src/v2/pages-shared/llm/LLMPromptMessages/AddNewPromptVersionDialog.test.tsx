import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import AddNewPromptVersionDialog from "./AddNewPromptVersionDialog";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import {
  PROMPT_TEMPLATE_STRUCTURE,
  PromptVersion,
  PromptWithLatestVersion,
} from "@/types/prompts";

const mockToast = vi.fn();
const mockCreateVersion = vi.fn();
const mockCreatePrompt = vi.fn();
let mockIsSavingVersion = false;
let mockIsCreatingPrompt = false;

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

vi.mock("@/api/prompts/useCreatePromptVersionMutation", () => ({
  default: () => ({
    mutate: mockCreateVersion,
    isPending: mockIsSavingVersion,
  }),
}));

vi.mock("@/api/prompts/usePromptCreateMutation", () => ({
  default: () => ({
    mutate: mockCreatePrompt,
    isPending: mockIsCreatingPrompt,
  }),
}));

vi.mock("@/api/prompts/usePromptById", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

vi.mock("@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox", () => ({
  default: () => <div data-testid="prompts-select-box" />,
}));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({ activeWorkspaceName: "test-workspace" }),
  ),
  useActiveProjectId: () => "test-project-id",
}));

const VERSION: PromptVersion = {
  id: "version-2",
  template: "[]",
  metadata: {},
  commit: "abc",
  prompt_id: "prompt-1",
  created_at: "2026-01-01T00:00:00Z",
};

const EXISTING_PROMPT: PromptWithLatestVersion = {
  id: "prompt-1",
  name: "Support bot",
  description: "",
  last_updated_at: "2026-01-01T00:00:00Z",
  created_at: "2026-01-01T00:00:00Z",
  version_count: 1,
  tags: [],
  latest_version: VERSION,
};

const renderDialog = (
  props: Partial<React.ComponentProps<typeof AddNewPromptVersionDialog>>,
) =>
  render(
    <PermissionsProvider value={DEFAULT_PERMISSIONS}>
      <AddNewPromptVersionDialog
        open
        setOpen={vi.fn()}
        template='[{"role":"user","content":"Hi"}]'
        onSave={vi.fn()}
        {...props}
      />
    </PermissionsProvider>,
  );

const saveButton = () =>
  screen.getByRole("button", { name: "Save to library" });

const clickSave = () => fireEvent.click(saveButton());

beforeEach(() => {
  vi.clearAllMocks();
  mockIsSavingVersion = false;
  mockIsCreatingPrompt = false;
});

describe("AddNewPromptVersionDialog success toast", () => {
  it.each([
    [PROMPT_TEMPLATE_STRUCTURE.CHAT, 'Saved new chat prompt "My prompt"'],
    [PROMPT_TEMPLATE_STRUCTURE.TEXT, 'Saved new prompt "My prompt"'],
  ])(
    "confirms a new %s prompt once it is created",
    (templateStructure, expected) => {
      const onSave = vi.fn();
      renderDialog({ templateStructure, onSave });

      fireEvent.change(screen.getByLabelText("Name"), {
        target: { value: "My prompt" },
      });
      clickSave();

      expect(mockToast).not.toHaveBeenCalled();

      const [, { onSuccess }] = mockCreatePrompt.mock.calls[0];
      onSuccess({ ...EXISTING_PROMPT, name: "My prompt" });

      expect(mockToast).toHaveBeenCalledTimes(1);
      expect(mockToast).toHaveBeenCalledWith({ description: expected });
      expect(onSave).toHaveBeenCalledWith(VERSION, "My prompt", "prompt-1");
    },
  );

  it.each([
    [
      PROMPT_TEMPLATE_STRUCTURE.CHAT,
      'Saved new version of chat prompt "Support bot"',
    ],
    [
      PROMPT_TEMPLATE_STRUCTURE.TEXT,
      'Saved new version of prompt "Support bot"',
    ],
  ])(
    "confirms a new version of an existing %s prompt once it is saved",
    (templateStructure, expected) => {
      const onSave = vi.fn();
      renderDialog({ templateStructure, prompt: EXISTING_PROMPT, onSave });

      clickSave();

      expect(mockToast).not.toHaveBeenCalled();

      const [{ onSuccess }] = mockCreateVersion.mock.calls[0];
      onSuccess(VERSION);

      expect(mockToast).toHaveBeenCalledTimes(1);
      expect(mockToast).toHaveBeenCalledWith({ description: expected });
      expect(onSave).toHaveBeenCalledWith(VERSION, "Support bot", "prompt-1");
    },
  );
});

describe("AddNewPromptVersionDialog while a save is in flight", () => {
  it("keeps Save disabled while a new prompt is being created", () => {
    mockIsCreatingPrompt = true;
    renderDialog({ defaultName: "My prompt" });

    expect(saveButton()).toBeDisabled();
  });

  it("keeps Save disabled while a new version is being saved", () => {
    mockIsSavingVersion = true;
    renderDialog({ prompt: EXISTING_PROMPT });

    expect(saveButton()).toBeDisabled();
  });
});
