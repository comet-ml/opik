import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import DatasetItemsEmptyContent from "./DatasetItemsEmptyContent";

vi.mock("@/contexts/theme-provider", () => ({
  useTheme: () => ({ themeMode: "light" }),
}));

const onAddItem = vi.fn();

const renderContent = (
  canEditDatasets: boolean,
  hasDeletedItemsInDraft = false,
) =>
  render(
    <PermissionsTestProvider permissions={{ canEditDatasets }}>
      <DatasetItemsEmptyContent
        title="No test cases yet"
        itemName="test case"
        hasDeletedItemsInDraft={hasDeletedItemsInDraft}
        onAddItem={onAddItem}
      />
    </PermissionsTestProvider>,
  );

describe("DatasetItemsEmptyContent", () => {
  it("offers adding an item when editing is allowed", () => {
    renderContent(true);

    expect(
      screen.getByText(
        "Add test cases to run evaluations and measure performance.",
      ),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("dataset-items-empty-add-button"));
    expect(onAddItem).toHaveBeenCalledTimes(1);
  });

  it("hides the add button and the add-items hint without edit permission", () => {
    renderContent(false);

    expect(screen.queryByTestId("dataset-items-empty-add-button")).toBeNull();
    expect(screen.queryByText(/Add test cases/)).toBeNull();
    expect(
      screen.getByText("Users with edit access can add test cases."),
    ).toBeInTheDocument();
  });

  it("shows no description or add button when every item on the page is deleted in the draft", () => {
    renderContent(true, true);

    expect(screen.queryByTestId("dataset-items-empty-add-button")).toBeNull();
    expect(screen.queryByText(/Add test cases/)).toBeNull();
  });
});
