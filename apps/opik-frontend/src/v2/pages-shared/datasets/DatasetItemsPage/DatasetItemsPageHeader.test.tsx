import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { Dataset } from "@/types/datasets";
import DatasetItemsPageHeader from "./DatasetItemsPageHeader";

vi.mock("@tanstack/react-router", () => ({
  useRouter: () => ({
    basepath: "/",
    options: { parseSearch: () => ({}) },
  }),
}));

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [undefined, vi.fn()],
}));

vi.mock("@/shared/BackButton/BackButton", () => ({ default: () => null }));
vi.mock("@/v2/pages-shared/datasets/UseDatasetDropdown", () => ({
  default: () => null,
}));

const buildDataset = (overrides: Partial<Dataset> = {}): Dataset => ({
  id: "d1",
  name: "Suite",
  tags: [],
  dataset_items_count: 1,
  experiment_count: 0,
  most_recent_experiment_at: "",
  last_created_experiment_at: "",
  most_recent_optimization_at: "",
  last_created_optimization_at: "",
  optimization_count: 0,
  created_at: "2026-09-01T00:00:00Z",
  last_updated_at: "2026-09-01T00:00:00Z",
  ...overrides,
});

const onSettingsClick = vi.fn();

const renderHeader = (
  canEditDatasets: boolean,
  { isTestSuite = true, dataset = buildDataset(), hasDraft = false } = {},
) =>
  render(
    <TooltipProvider>
      <DatasetItemsPageHeader
        dataset={dataset}
        datasetId="d1"
        isTestSuite={isTestSuite}
        entityName={isTestSuite ? "test suite" : "dataset"}
        activeProjectId="p1"
        hasDraft={hasDraft}
        canEditDatasets={canEditDatasets}
        effectiveAssertions={["is polite"]}
        onAddTag={vi.fn()}
        onDeleteTag={vi.fn()}
        onDiscardClick={vi.fn()}
        onSaveClick={vi.fn()}
        onSettingsClick={onSettingsClick}
        onAddItem={vi.fn()}
        onExpand={vi.fn()}
      />
    </TooltipProvider>,
  );

describe("DatasetItemsPageHeader", () => {
  it("shows test settings and opens them from the assertions pill when editing is allowed", () => {
    renderHeader(true);

    expect(screen.getByTestId("dataset-header-settings-button")).toBeVisible();

    onSettingsClick.mockClear();
    fireEvent.click(
      screen.getByTestId("dataset-detail-global-assertions-pill"),
    );
    expect(onSettingsClick).toHaveBeenCalledTimes(1);
  });

  it("hides test settings and keeps the assertions pill read-only without edit permission", () => {
    renderHeader(false);

    expect(screen.queryByTestId("dataset-header-settings-button")).toBeNull();

    onSettingsClick.mockClear();
    const pill = screen.getByTestId("dataset-detail-global-assertions-pill");
    expect(pill).toHaveTextContent("1 global assertion");
    fireEvent.click(pill);
    expect(onSettingsClick).not.toHaveBeenCalled();
  });

  it("shows the Expand with AI and add buttons when editing is allowed", () => {
    renderHeader(true);

    expect(screen.getByTestId("dataset-header-expand-button")).toBeVisible();
    expect(screen.getByTestId("dataset-header-add-button")).toHaveTextContent(
      "Test case",
    );
  });

  it("hides the Expand with AI and add buttons without edit permission", () => {
    renderHeader(false);

    expect(screen.queryByTestId("dataset-header-expand-button")).toBeNull();
    expect(screen.queryByTestId("dataset-header-add-button")).toBeNull();
  });

  it("labels the add button 'Record' for datasets", () => {
    renderHeader(true, { isTestSuite: false });

    expect(screen.getByTestId("dataset-header-add-button")).toHaveTextContent(
      "Record",
    );
  });

  it("lets users with edit permission add tags", () => {
    renderHeader(true);

    expect(screen.getByTestId("add-tag-button")).toBeInTheDocument();
  });

  it("shows existing tags read-only without edit permission", () => {
    renderHeader(false, { dataset: buildDataset({ tags: ["prod"] }) });

    expect(screen.getByText("prod")).toBeInTheDocument();
    expect(screen.queryByTestId("add-tag-button")).toBeNull();
  });

  it("shows Save and Discard for a draft when editing is allowed", () => {
    renderHeader(true, { hasDraft: true });

    expect(screen.getByTestId("dataset-items-commit-button")).toBeVisible();
    expect(screen.getByTestId("dataset-items-discard-button")).toBeVisible();
  });

  it("keeps only Discard for a draft without edit permission", () => {
    renderHeader(false, { hasDraft: true });

    expect(screen.queryByTestId("dataset-items-commit-button")).toBeNull();
    expect(screen.getByTestId("dataset-items-discard-button")).toBeVisible();
  });
});
