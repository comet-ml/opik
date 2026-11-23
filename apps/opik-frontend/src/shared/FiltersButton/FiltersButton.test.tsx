import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import { TooltipProvider } from "@/ui/tooltip";

const COLUMNS = [{ id: "name", label: "Name", type: COLUMN_TYPE.string }];

const buildFilter = (value: string): Filter => ({
  id: "1",
  field: "name",
  type: COLUMN_TYPE.string,
  operator: "=",
  value,
});

const renderButton = (filters: Filter[]) =>
  render(
    <TooltipProvider>
      <FiltersButton
        columns={COLUMNS}
        filters={filters}
        onChange={vi.fn()}
        layout="add"
      />
    </TooltipProvider>,
  );

describe("FiltersButton add layout", () => {
  it("shows the Add filter chip when no filter is set", () => {
    renderButton([]);

    expect(
      screen.getByRole("button", { name: "Add filter" }),
    ).toBeInTheDocument();
  });

  it("keeps the chip when the only filter has no value", () => {
    renderButton([buildFilter("")]);

    expect(
      screen.getByRole("button", { name: "Add filter" }),
    ).toBeInTheDocument();
  });

  it("shows the active filter count once a filter is applied", () => {
    renderButton([buildFilter("exp")]);

    expect(
      screen.queryByRole("button", { name: "Add filter" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "1" })).toBeInTheDocument();
  });

  it("opens the Filters popover from the chip", () => {
    renderButton([]);

    fireEvent.click(screen.getByRole("button", { name: "Add filter" }));

    expect(screen.getByText("Filters")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Clear all" }),
    ).toBeInTheDocument();
  });
});
