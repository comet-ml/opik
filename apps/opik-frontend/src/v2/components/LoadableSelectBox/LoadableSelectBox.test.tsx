import { ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import LoadableSelectBox from "./LoadableSelectBox";
import { TooltipProvider } from "@/ui/tooltip";
import { DropdownOption } from "@/types/shared";

const OPTIONS: DropdownOption<string>[] = [
  { value: "a", label: "Alpha" },
  { value: "b", label: "Beta" },
  { value: "c", label: "Gamma" },
];

// The app mounts a TooltipProvider at the root; supply one for isolated renders
// so the multi-select trigger's tooltip can mount.
const renderWithProviders = (ui: ReactElement) =>
  render(ui, { wrapper: TooltipProvider });

// Radix Popover content is hard to drive in happy-dom, so these assert the
// collapsed-trigger render (title / placeholder logic) rather than opening the
// menu — see shared/ColumnsButton/ColumnsButton.test.tsx for the same rationale.
describe("LoadableSelectBox (v2)", () => {
  it("shows the placeholder when nothing is selected", () => {
    renderWithProviders(
      <LoadableSelectBox
        options={OPTIONS}
        value=""
        onChange={vi.fn()}
        placeholder="Pick one"
      />,
    );
    expect(screen.getByText("Pick one")).toBeInTheDocument();
  });

  it("shows the selected label in the trigger (single-select)", () => {
    renderWithProviders(
      <LoadableSelectBox options={OPTIONS} value="b" onChange={vi.fn()} />,
    );
    expect(screen.getByText("Beta")).toBeInTheDocument();
  });

  it("joins the selected labels (multi-select)", () => {
    renderWithProviders(
      <LoadableSelectBox
        multiselect
        options={OPTIONS}
        value={["a", "c"]}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByText("Alpha, Gamma")).toBeInTheDocument();
  });

  it("shows the select-all label when everything is selected (multi-select)", () => {
    renderWithProviders(
      <LoadableSelectBox
        multiselect
        options={OPTIONS}
        value={["a", "b", "c"]}
        onChange={vi.fn()}
        selectAllLabel="All selected"
      />,
    );
    expect(screen.getByText("All selected")).toBeInTheDocument();
  });
});
