import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import DefaultRow from "./DefaultRow";
import { Group, GroupRowConfig } from "@/types/groups";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

const buildGroup = (overrides: Partial<Group> = {}): Group => ({
  id: "group-1",
  field: "tags",
  type: COLUMN_TYPE.list,
  direction: SORT_DIRECTION.ASC,
  key: "",
  ...overrides,
});

const renderRow = (group: Group, config?: GroupRowConfig) =>
  render(
    <table>
      <tbody>
        <tr>
          <DefaultRow group={group} config={config} onChange={vi.fn()} />
        </tr>
      </tbody>
    </table>,
  );

describe("DefaultRow", () => {
  it("shows no sort field before a column is chosen", () => {
    renderRow(buildGroup({ field: "", type: "" as COLUMN_TYPE }));

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("shows the sort direction selector when the column's sort is selectable", () => {
    renderRow(buildGroup());

    expect(screen.getByRole("combobox")).toBeInTheDocument();
    expect(screen.getByText("Ascending")).toBeInTheDocument();
  });

  it("shows a fixed sort order as text instead of a selector", () => {
    renderRow(buildGroup({ field: "dataset_id", type: COLUMN_TYPE.string }), {
      sortingMessage: "Sorted by last experiment created",
    } as GroupRowConfig);

    expect(
      screen.getByText("Sorted by last experiment created"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });
});
