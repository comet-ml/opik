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

const onSettingsClick = vi.fn();

const renderHeader = (canEditDatasets: boolean) =>
  render(
    <TooltipProvider>
      <DatasetItemsPageHeader
        dataset={{ id: "d1", name: "Suite", tags: [] } as unknown as Dataset}
        datasetId="d1"
        isTestSuite
        entityName="test suite"
        activeProjectId="p1"
        hasDraft={false}
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
});
