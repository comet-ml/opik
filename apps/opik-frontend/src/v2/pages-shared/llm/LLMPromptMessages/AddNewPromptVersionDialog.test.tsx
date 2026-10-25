import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";

import {
  PROMPT_TEMPLATE_STRUCTURE,
  PromptWithLatestVersion,
} from "@/types/prompts";
import { Toast, ToastProvider, ToastViewport } from "@/ui/toast";
import AddNewPromptVersionDialog from "./AddNewPromptVersionDialog";

const newVersionMutate = vi.fn();
const createMutate = vi.fn();
const toast = vi.fn();
const navigate = vi.fn();
let canViewPrompts = true;

vi.mock("@/api/prompts/useCreatePromptVersionMutation", () => ({
  default: () => ({ mutate: newVersionMutate, isPending: false }),
}));

vi.mock("@/api/prompts/usePromptCreateMutation", () => ({
  default: () => ({ mutate: createMutate, isPending: false }),
}));

vi.mock("@/api/prompts/usePromptById", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "default" }),
  useActiveProjectId: () => "project-id",
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: {
      canViewPrompts,
      canCreatePrompts: true,
      canEditPrompts: true,
    },
  }),
}));

vi.mock("@/hooks/useCodemirrorTheme", () => ({
  useCodemirrorTheme: () => undefined,
}));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useNavigate: () => navigate,
}));

vi.mock("@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox", () => ({
  default: () => null,
}));

const LIBRARY_PROMPT = {
  id: "prompt-id",
  name: "Support reply",
  latest_version: { id: "version-id", template: "Hi" },
} as unknown as PromptWithLatestVersion;

const SAVED_PROMPT = {
  id: "prompt-id",
  name: "Support reply",
  latest_version: { id: "version-id" },
};

const SAVED_VERSION = { id: "new-version-id", prompt_id: "prompt-id" };

const setOpen = vi.fn();
const onSave = vi.fn();

const renderDialog = ({
  open = true,
  prompt,
  templateStructure,
}: {
  open?: boolean;
  prompt?: PromptWithLatestVersion;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
} = {}) => (
  <AddNewPromptVersionDialog
    open={open}
    setOpen={setOpen}
    prompt={prompt}
    template="Hello {{name}}"
    templateStructure={templateStructure}
    defaultName="Support reply"
    onSave={onSave}
  />
);

const callOptionsOf = (mutate: ReturnType<typeof vi.fn>) =>
  mutate.mock.calls[0][1] as {
    onSuccess: (data?: unknown) => void;
    onError: () => void;
  };

// Calls back in the same order as useMutation: the hook's own onSuccess (which
// runs the onSuccess passed in the mutation variables) first, then mutate's.
const finishSave = (mutate: ReturnType<typeof vi.fn>, data: unknown) =>
  act(() => {
    mutate.mock.calls[0][0].onSuccess?.(data);
    callOptionsOf(mutate).onSuccess(data);
  });

const saveButton = () =>
  screen.getByRole("button", { name: "Save to library" });

const clickGoToPrompt = () => {
  render(
    <ToastProvider>
      <Toast open>{toast.mock.calls[0][0].actions}</Toast>
      <ToastViewport />
    </ToastProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Go to prompt" }));
};

beforeEach(() => {
  newVersionMutate.mockClear();
  createMutate.mockClear();
  toast.mockClear();
  navigate.mockClear();
  canViewPrompts = true;
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

describe("AddNewPromptVersionDialog success toast", () => {
  it.each([
    [PROMPT_TEMPLATE_STRUCTURE.CHAT, 'Saved new chat prompt "Support reply"'],
    [PROMPT_TEMPLATE_STRUCTURE.TEXT, 'Saved new prompt "Support reply"'],
  ])(
    "confirms a new %s prompt once it is created",
    (templateStructure, expected) => {
      render(renderDialog({ templateStructure }));
      fireEvent.click(saveButton());

      expect(toast).not.toHaveBeenCalled();

      finishSave(createMutate, SAVED_PROMPT);

      expect(toast).toHaveBeenCalledTimes(1);
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({ description: expected }),
      );
      expect(onSave).toHaveBeenCalledWith(
        SAVED_PROMPT.latest_version,
        "Support reply",
        "prompt-id",
      );
    },
  );

  it.each([
    [
      PROMPT_TEMPLATE_STRUCTURE.CHAT,
      'Saved new version of chat prompt "Support reply"',
    ],
    [
      PROMPT_TEMPLATE_STRUCTURE.TEXT,
      'Saved new version of prompt "Support reply"',
    ],
  ])(
    "confirms a new version of an existing %s prompt once it is saved",
    (templateStructure, expected) => {
      render(renderDialog({ templateStructure, prompt: LIBRARY_PROMPT }));
      fireEvent.click(saveButton());

      expect(toast).not.toHaveBeenCalled();

      finishSave(newVersionMutate, SAVED_VERSION);

      expect(toast).toHaveBeenCalledTimes(1);
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({ description: expected }),
      );
      expect(onSave).toHaveBeenCalledWith(
        SAVED_VERSION,
        "Support reply",
        "prompt-id",
      );
    },
  );
});

describe("AddNewPromptVersionDialog success toast link", () => {
  const PROMPT_PAGE = {
    to: "/$workspaceName/projects/$projectId/prompts/$promptId",
    params: {
      workspaceName: "default",
      projectId: "project-id",
      promptId: "prompt-id",
    },
  };

  it("goes to the new prompt", () => {
    render(renderDialog());
    fireEvent.click(saveButton());
    finishSave(createMutate, SAVED_PROMPT);

    clickGoToPrompt();

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith({ ...PROMPT_PAGE, search: {} });
  });

  it("goes to the new version of an existing prompt", () => {
    render(renderDialog({ prompt: LIBRARY_PROMPT }));
    fireEvent.click(saveButton());
    finishSave(newVersionMutate, SAVED_VERSION);

    clickGoToPrompt();

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith({
      ...PROMPT_PAGE,
      search: { activeVersionId: "new-version-id" },
    });
  });

  it("shows no link when the created prompt comes back without an id", () => {
    render(renderDialog());
    fireEvent.click(saveButton());
    finishSave(createMutate, { name: "Support reply" });

    expect(toast).toHaveBeenCalledWith({
      description: 'Saved new prompt "Support reply"',
    });
    expect(toast.mock.calls[0][0].actions).toBeUndefined();
  });

  it("shows no link on a new prompt when the user cannot view prompts", () => {
    canViewPrompts = false;
    render(renderDialog());
    fireEvent.click(saveButton());
    finishSave(createMutate, SAVED_PROMPT);

    expect(toast).toHaveBeenCalledWith({
      description: 'Saved new prompt "Support reply"',
    });
    expect(toast.mock.calls[0][0].actions).toBeUndefined();
  });

  it("shows no link on a new version when the user cannot view prompts", () => {
    canViewPrompts = false;
    render(renderDialog({ prompt: LIBRARY_PROMPT }));
    fireEvent.click(saveButton());
    finishSave(newVersionMutate, SAVED_VERSION);

    expect(toast).toHaveBeenCalledWith({
      description: 'Saved new version of prompt "Support reply"',
    });
    expect(toast.mock.calls[0][0].actions).toBeUndefined();
  });
});
