import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { Dataset } from "@/types/datasets";
import { Permissions } from "@/types/permissions";
import CreateDatasetSidebar from "./CreateDatasetSidebar";

const NEW_SUITE = { id: "suite-id", name: "my-suite" } as Dataset;

const createMutate = vi.fn(
  (_: unknown, options: { onSuccess?: (dataset: Dataset) => void }) =>
    options.onSuccess?.(NEW_SUITE),
);
const changesMutate = vi.fn();
const csvMutate = vi.fn();
const jsonMutate = vi.fn();

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-id",
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {},
  trackEvent: vi.fn(),
}));
vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: { name: "project" } }),
}));
vi.mock("@/api/datasets/useDatasetCreateMutation", () => ({
  default: () => ({ mutate: createMutate }),
}));
vi.mock("@/api/datasets/useDatasetUpdateMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/datasets/useDatasetItemChangesMutation", () => ({
  default: () => ({ mutate: changesMutate }),
}));
vi.mock("@/api/datasets/useDatasetItemsFromCsvMutation", () => ({
  default: () => ({ mutate: csvMutate }),
}));
vi.mock("@/api/datasets/useDatasetItemsFromJsonMutation", () => ({
  default: () => ({ mutate: jsonMutate }),
}));
vi.mock("@/shared/ResizableSidePanel/ResizableSidePanel", () => ({
  default: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock("@/shared/ResizableSidePanel/ResizableSidePanelTopBar", () => ({
  default: () => null,
}));
vi.mock("@/shared/CodeHighlighter/CodeHighlighter", () => ({
  default: () => <div>code</div>,
}));
vi.mock("@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection", () => ({
  default: () => <div>evaluation-criteria</div>,
}));
vi.mock("@/v2/pages-shared/datasets/DatasetCsvDropzone", () => ({
  default: ({ onFileSelect }: { onFileSelect: (file: File) => void }) => (
    <button onClick={() => onFileSelect(new File(["input\na\n"], "items.csv"))}>
      dropzone
    </button>
  ),
}));

const renderSidebar = (
  mode: "upload" | "sdk",
  permissions: Partial<Permissions> = {},
) =>
  render(
    <PermissionsTestProvider permissions={permissions}>
      <QueryClientProvider client={new QueryClient()}>
        <CreateDatasetSidebar
          type="test_suite"
          mode={mode}
          open
          setOpen={vi.fn()}
        />
      </QueryClientProvider>
    </PermissionsTestProvider>,
  );

describe("CreateDatasetSidebar upload mode", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows the dropzone and, after a file is picked, pass criteria with dataset edit permission", () => {
    renderSidebar("upload");

    fireEvent.click(screen.getByText("dropzone"));

    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByText("Advanced options")).toBeInTheDocument();
  });

  it("shows only name and description without dataset edit permission", () => {
    renderSidebar("upload", { canEditDatasets: false });

    expect(screen.queryByText("dropzone")).not.toBeInTheDocument();
    expect(screen.queryByText("Advanced options")).not.toBeInTheDocument();
    expect(screen.queryByText("evaluation-criteria")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByLabelText(/Description/)).toBeInTheDocument();
  });

  it("creates without follow-up item calls without dataset edit permission", async () => {
    renderSidebar("upload", { canEditDatasets: false });

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "my-suite" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create test suite" }));

    await waitFor(() => expect(createMutate).toHaveBeenCalledTimes(1));
    expect(changesMutate).not.toHaveBeenCalled();
    expect(csvMutate).not.toHaveBeenCalled();
    expect(jsonMutate).not.toHaveBeenCalled();
  });
});

describe("CreateDatasetSidebar sdk mode", () => {
  it("is unchanged without dataset edit permission", () => {
    renderSidebar("sdk", { canEditDatasets: false });

    expect(screen.getByText("Python")).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
  });
});
