import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { Permissions } from "@/types/permissions";
import { Trace } from "@/types/traces";
import AddToDropdown from "./AddToDropdown";

vi.mock(
  "@/v2/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog",
  () => ({ default: () => null }),
);
vi.mock(
  "@/v2/pages-shared/traces/AddToTestSuiteDialog/AddToTestSuiteDialog",
  () => ({ default: () => null }),
);
vi.mock("@/v2/pages-shared/traces/AddToQueueDialog/AddToQueueDialog", () => ({
  default: () => null,
}));

const renderDropdown = (
  permissions: Partial<Permissions> = {},
  dataType: "traces" | "spans" | "threads" = "traces",
) =>
  render(
    <PermissionsTestProvider permissions={permissions}>
      <AddToDropdown
        getDataForExport={() => Promise.resolve([])}
        selectedRows={[{ id: "trace-1" } as Trace]}
        dataType={dataType}
      />
    </PermissionsTestProvider>,
  );

const openMenu = () => {
  fireEvent.keyDown(screen.getByRole("button", { name: /add to/i }), {
    key: "Enter",
  });
};

describe("AddToDropdown", () => {
  it("offers dataset, test suite and annotation queue when allowed", () => {
    renderDropdown();
    openMenu();

    expect(
      screen.getByRole("menuitem", { name: "Dataset" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Test suite" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Annotation queue" }),
    ).toBeInTheDocument();
  });

  it.each([
    ["dataset edit", { canEditDatasets: false }],
    ["dataset view", { canViewDatasets: false }],
    ["project data view", { canViewProjectData: false }],
  ])("hides dataset and test suite without %s permission", (_, permissions) => {
    renderDropdown(permissions);
    openMenu();

    expect(screen.queryByRole("menuitem", { name: "Dataset" })).toBeNull();
    expect(screen.queryByRole("menuitem", { name: "Test suite" })).toBeNull();
    expect(
      screen.getByRole("menuitem", { name: "Annotation queue" }),
    ).toBeInTheDocument();
  });

  it("offers only dataset and test suite for spans", () => {
    renderDropdown({}, "spans");
    openMenu();

    expect(
      screen.getByRole("menuitem", { name: "Dataset" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Test suite" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("menuitem", { name: "Annotation queue" }),
    ).toBeNull();
  });

  it("offers only the annotation queue for threads", () => {
    renderDropdown({}, "threads");
    openMenu();

    expect(screen.queryByRole("menuitem", { name: "Dataset" })).toBeNull();
    expect(screen.queryByRole("menuitem", { name: "Test suite" })).toBeNull();
    expect(
      screen.getByRole("menuitem", { name: "Annotation queue" }),
    ).toBeInTheDocument();
  });

  it("renders nothing when no option is available", () => {
    renderDropdown({ canEditDatasets: false }, "spans");

    expect(screen.queryByRole("button", { name: /add to/i })).toBeNull();
  });
});
