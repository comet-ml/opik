import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";

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

const setOpen = vi.fn();
const onSave = vi.fn();

const renderDialog = ({
  open = true,
  prompt,
}: { open?: boolean; prompt?: PromptWithLatestVersion } = {}) => (
  <AddNewPromptVersionDialog
    open={open}
    setOpen={setOpen}
    prompt={prompt}
    template="Hello {{name}}"
    defaultName="Support reply"
    onSave={onSave}
  />
);

const callOptionsOf = (mutate: ReturnType<typeof vi.fn>) =>
  mutate.mock.calls[0][1] as {
    onSuccess: (data?: unknown) => void;
    onError: () => void;
  };

const saveButton = () =>
  screen.getByRole("button", { name: "Save to library" });

beforeEach(() => {
  newVersionMutate.mockClear();
  createMutate.mockClear();
  setOpen.mockClear();
  onSave.mockClear();
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

describe("AddNewPromptVersionDialog while saving", () => {
  it("stays open with a spinner until a new prompt is saved, then closes", () => {
    render(renderDialog());
    fireEvent.click(saveButton());

    expect(setOpen).not.toHaveBeenCalledWith(false);
    expect(saveButton().querySelector(".animate-spin")).not.toBeNull();

    act(() =>
      callOptionsOf(createMutate).onSuccess({
        id: "prompt-id",
        name: "Support reply",
        latest_version: { id: "version-id" },
      }),
    );

    expect(onSave).toHaveBeenCalledWith(
      { id: "version-id" },
      "Support reply",
      "prompt-id",
    );
    expect(setOpen).toHaveBeenCalledWith(false);
  });

  it("closes once a new version of an existing prompt is saved", () => {
    render(renderDialog({ prompt: LIBRARY_PROMPT }));
    fireEvent.click(saveButton());

    expect(setOpen).not.toHaveBeenCalledWith(false);

    act(() => callOptionsOf(newVersionMutate).onSuccess());

    expect(setOpen).toHaveBeenCalledWith(false);
  });

  it("stays open and lets you retry when the save fails", () => {
    render(renderDialog());
    fireEvent.click(saveButton());

    act(() => callOptionsOf(createMutate).onError());

    expect(setOpen).not.toHaveBeenCalledWith(false);
    expect(saveButton()).toBeEnabled();
    expect(saveButton().querySelector(".animate-spin")).toBeNull();
  });
});
