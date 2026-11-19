import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { Dataset } from "@/types/datasets";
import { Permissions } from "@/types/permissions";
import AddEditTestSuiteDialog from "./AddEditTestSuiteDialog";

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
vi.mock("@/shared/AssertionField/AssertionsField", () => ({
  default: () => <div>assertions-field</div>,
}));

const renderDialog = (permissions: Partial<Permissions> = {}) =>
  render(
    <PermissionsTestProvider permissions={permissions}>
      <QueryClientProvider client={new QueryClient()}>
        <AddEditTestSuiteDialog open setOpen={vi.fn()} />
      </QueryClientProvider>
    </PermissionsTestProvider>,
  );

describe("AddEditTestSuiteDialog create", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows upload and pass criteria with dataset edit permission", () => {
    renderDialog();

    expect(screen.getByText("Upload a CSV or JSON file")).toBeInTheDocument();
    expect(screen.getByLabelText("Default runs per item")).toBeInTheDocument();
    expect(screen.getByText("assertions-field")).toBeInTheDocument();
  });

  it("hides upload and pass criteria without dataset edit permission", () => {
    renderDialog({ canEditDatasets: false });

    expect(screen.getByPlaceholderText("Name")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Description")).toBeInTheDocument();
    expect(
      screen.queryByText("Upload a CSV or JSON file"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText("Default runs per item"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("assertions-field")).not.toBeInTheDocument();
  });

  it("creates without follow-up item calls without dataset edit permission", async () => {
    renderDialog({ canEditDatasets: false });

    fireEvent.change(screen.getByPlaceholderText("Name"), {
      target: { value: "my-suite" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create new" }));

    await waitFor(() => expect(createMutate).toHaveBeenCalledTimes(1));
    expect(changesMutate).not.toHaveBeenCalled();
    expect(csvMutate).not.toHaveBeenCalled();
    expect(jsonMutate).not.toHaveBeenCalled();
  });
});
