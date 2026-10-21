import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { PromptWithLatestVersion } from "@/types/prompts";
import AddNewPromptVersionDialog from "./AddNewPromptVersionDialog";

const post = vi.fn();
const get = vi.fn();
const toast = vi.fn();
const onSave = vi.fn();

vi.mock("@/api/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/api")>()),
  default: {
    post: (...args: unknown[]) => post(...args),
    get: (...args: unknown[]) => get(...args),
  },
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
      canViewPrompts: true,
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
  useNavigate: () => vi.fn(),
}));

vi.mock("@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox", () => ({
  default: () => null,
}));

const LIBRARY_PROMPT = {
  id: "prompt-id",
  name: "Support reply",
  latest_version: { id: "version-id", template: "Hi" },
} as unknown as PromptWithLatestVersion;

let queryClient: QueryClient;

const renderDialog = ({
  open = true,
  mounted = true,
  prompt,
}: {
  open?: boolean;
  mounted?: boolean;
  prompt?: PromptWithLatestVersion;
} = {}) => (
  <QueryClientProvider client={queryClient}>
    {mounted && (
      <AddNewPromptVersionDialog
        open={open}
        setOpen={vi.fn()}
        prompt={prompt}
        template="Hello {{name}}"
        defaultName="Support reply"
        onSave={onSave}
      />
    )}
  </QueryClientProvider>
);

const holdSaveResponse = () => {
  let respond!: (response: unknown) => void;
  post.mockReturnValueOnce(
    new Promise((resolve) => {
      respond = resolve;
    }),
  );
  return respond;
};

const respondWithNewPrompt = (respond: (response: unknown) => void) => {
  get.mockResolvedValueOnce({
    data: {
      id: "prompt-id",
      name: "Support reply",
      latest_version: { id: "version-id" },
    },
  });
  respond({ data: "", headers: { location: "/v1/private/prompts/prompt-id" } });
};

const expectOneToast = async (description: string) => {
  await waitFor(() =>
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ description }),
    ),
  );
  expect(toast).toHaveBeenCalledTimes(1);
};

const saveButton = () =>
  screen.getByRole("button", { name: "Save to library" });

beforeEach(() => {
  queryClient = new QueryClient();
  post.mockReset();
  get.mockReset();
  toast.mockReset();
  onSave.mockReset();
});

describe("AddNewPromptVersionDialog toast when the save ends after the dialog", () => {
  it("was closed, for a new prompt", async () => {
    const respond = holdSaveResponse();
    const { rerender } = render(renderDialog());
    fireEvent.click(saveButton());

    rerender(renderDialog({ open: false }));
    respondWithNewPrompt(respond);

    await expectOneToast('Saved new prompt "Support reply"');
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("was unmounted, for a new prompt", async () => {
    const respond = holdSaveResponse();
    const { rerender } = render(renderDialog());
    fireEvent.click(saveButton());

    rerender(renderDialog({ mounted: false }));
    respondWithNewPrompt(respond);

    await expectOneToast('Saved new prompt "Support reply"');
  });

  it("was unmounted, for a new version of an existing prompt", async () => {
    const respond = holdSaveResponse();
    const { rerender } = render(renderDialog({ prompt: LIBRARY_PROMPT }));
    fireEvent.click(saveButton());

    rerender(renderDialog({ mounted: false }));
    respond({ data: { id: "new-version-id", prompt_id: "prompt-id" } });

    await expectOneToast('Saved new version of prompt "Support reply"');
  });
});
