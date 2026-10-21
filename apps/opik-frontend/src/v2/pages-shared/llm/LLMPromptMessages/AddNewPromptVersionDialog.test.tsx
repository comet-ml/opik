import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { PromptWithLatestVersion } from "@/types/prompts";
import AddNewPromptVersionDialog from "./AddNewPromptVersionDialog";

const newVersionMutate = vi.fn();
const createMutate = vi.fn();

vi.mock("@/api/prompts/useCreatePromptVersionMutation", () => ({
  default: () => ({ mutate: newVersionMutate, isPending: false }),
}));

vi.mock("@/api/prompts/usePromptCreateMutation", () => ({
  default: () => ({ mutate: createMutate, isPending: false }),
}));

vi.mock("@/api/prompts/usePromptById", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "default" }),
  useActiveProjectId: () => "project-id",
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: { canCreatePrompts: true, canEditPrompts: true },
  }),
}));

vi.mock("@/hooks/useCodemirrorTheme", () => ({
  useCodemirrorTheme: () => undefined,
}));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock("@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox", () => ({
  default: () => null,
}));

const LIBRARY_PROMPT = {
  id: "prompt-id",
  name: "Support reply",
  latest_version: { id: "version-id", template: "Hi" },
} as unknown as PromptWithLatestVersion;

const renderDialog = ({
  open = true,
  prompt,
}: { open?: boolean; prompt?: PromptWithLatestVersion } = {}) => (
  <AddNewPromptVersionDialog
    open={open}
    setOpen={vi.fn()}
    prompt={prompt}
    template="Hello {{name}}"
    defaultName="Support reply"
    onSave={vi.fn()}
  />
);

const saveButton = () =>
  screen.getByRole("button", { name: "Save to library" });

beforeEach(() => {
  newVersionMutate.mockClear();
  createMutate.mockClear();
});

describe("AddNewPromptVersionDialog double click", () => {
  it("creates a new prompt once", () => {
    render(renderDialog());

    fireEvent.click(saveButton());
    fireEvent.click(saveButton());

    expect(createMutate).toHaveBeenCalledTimes(1);
    expect(saveButton()).toBeDisabled();
  });

  it("adds one new version to an existing prompt", () => {
    render(renderDialog({ prompt: LIBRARY_PROMPT }));

    fireEvent.click(saveButton());
    fireEvent.click(saveButton());

    expect(newVersionMutate).toHaveBeenCalledTimes(1);
    expect(saveButton()).toBeDisabled();
  });

  it("can save again after the dialog is opened again", () => {
    const { rerender } = render(renderDialog());
    fireEvent.click(saveButton());

    rerender(renderDialog({ open: false }));
    rerender(renderDialog({ open: true }));

    expect(saveButton()).toBeEnabled();
  });
});
