import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import AddEditProjectDialog from "./AddEditProjectDialog";

const mockCreateMutate = vi.fn();
const mockNavigate = vi.fn();
const mockToast = vi.fn();
let mockIsCreating = false;

vi.mock("@/api/projects/useProjectCreateMutation", () => ({
  default: () => ({ mutate: mockCreateMutate, isPending: mockIsCreating }),
}));

vi.mock("@/api/projects/useProjectUpdateMutation", () => ({
  default: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => string) =>
    selector({ activeWorkspaceName: "test-workspace" }),
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canCreateProjects: true } }),
}));

const submitName = (name: string) => {
  fireEvent.change(screen.getByLabelText("Name"), {
    target: { value: name },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
};

describe("AddEditProjectDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsCreating = false;
  });

  it("closes and navigates to the project only after create succeeds", () => {
    const setOpen = vi.fn();
    render(<AddEditProjectDialog open setOpen={setOpen} />);

    submitName("my-project");

    expect(setOpen).not.toHaveBeenCalled();
    const [payload, { onSuccess }] = mockCreateMutate.mock.calls[0];
    expect(payload).toEqual({ project: { name: "my-project" } });

    onSuccess({ id: "project-id" });

    expect(setOpen).toHaveBeenCalledWith(false);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/home",
      params: { projectId: "project-id", workspaceName: "test-workspace" },
    });
  });

  it("stays open with the typed name when create fails", () => {
    const setOpen = vi.fn();
    render(<AddEditProjectDialog open setOpen={setOpen} />);

    submitName("rejected-name");

    expect(mockCreateMutate).toHaveBeenCalledTimes(1);
    expect(setOpen).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Name")).toHaveValue("rejected-name");
  });

  it("does not submit again while a create is pending", () => {
    mockIsCreating = true;
    render(<AddEditProjectDialog open setOpen={vi.fn()} />);

    submitName("my-project");

    expect(
      screen.getByRole("button", { name: "Create project" }),
    ).toBeDisabled();
    expect(mockCreateMutate).not.toHaveBeenCalled();
  });

  it("caps the name at the backend limit", () => {
    render(<AddEditProjectDialog open setOpen={vi.fn()} />);

    expect(screen.getByLabelText("Name")).toHaveAttribute("maxLength", "150");
  });
});
