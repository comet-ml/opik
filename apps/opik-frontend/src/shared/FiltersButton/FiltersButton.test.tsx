import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import { COLUMN_TYPE } from "@/types/shared";

const COLUMNS = [{ id: "name", label: "Name", type: COLUMN_TYPE.string }];

describe("FiltersButton add layout", () => {
  it("reads Add filter when no filter is set", () => {
    render(
      <FiltersButton
        columns={COLUMNS}
        filters={[]}
        onChange={vi.fn()}
        layout="add"
      />,
    );

    expect(
      screen.getByRole("button", { name: "Add filter" }),
    ).toBeInTheDocument();
  });

  it("shows the filter count once a filter is set", () => {
    render(
      <FiltersButton
        columns={COLUMNS}
        filters={[
          {
            id: "1",
            field: "name",
            type: COLUMN_TYPE.string,
            operator: "=",
            value: "exp",
          },
        ]}
        onChange={vi.fn()}
        layout="add"
      />,
    );

    expect(
      screen.getByRole("button", { name: "Filters (1)" }),
    ).toBeInTheDocument();
  });
});
