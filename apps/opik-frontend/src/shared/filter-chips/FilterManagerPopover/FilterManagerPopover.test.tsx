import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { COLUMN_TYPE } from "@/types/shared";
import { FilterOperator } from "@/types/filters";
import { QueryBuilderChipDefinition } from "@/shared/filter-chips/types";
import FilterManagerPopover from "./FilterManagerPopover";

const queryBuilder = (
  overrides: Partial<QueryBuilderChipDefinition> & {
    defaultOperator: FilterOperator;
  },
): QueryBuilderChipDefinition => ({
  id: "comments",
  field: "comments",
  label: "Comments",
  kind: "query-builder",
  columnType: COLUMN_TYPE.list,
  operators: ["is_not_empty", "is_empty"],
  ...overrides,
});

const selectFromMenu = (def: QueryBuilderChipDefinition) => {
  const onApplyValue = vi.fn();
  const onPinChip = vi.fn();
  render(
    <FilterManagerPopover
      trigger={<button>All filters</button>}
      open
      onOpenChange={vi.fn()}
      chipsPinned={[]}
      chipsUnpinned={[def]}
      values={{}}
      onApplyValue={onApplyValue}
      onPinChip={onPinChip}
      onUnpinChip={vi.fn()}
      onRequestOpenChip={vi.fn()}
    />,
  );
  fireEvent.click(screen.getByRole("menuitem", { name: def.label }));
  return { onApplyValue, onPinChip };
};

describe("FilterManagerPopover", () => {
  it("applies a query-builder chip whose default operator takes no value", () => {
    const { onApplyValue, onPinChip } = selectFromMenu(
      queryBuilder({ defaultOperator: "is_not_empty" }),
    );

    expect(onPinChip).toHaveBeenCalledWith("comments");
    expect(onApplyValue).toHaveBeenCalledWith("comments", {
      rows: [expect.objectContaining({ operator: "is_not_empty", value: "" })],
    });
  });

  it("does not apply a query-builder chip whose default operator needs a value", () => {
    const { onApplyValue } = selectFromMenu(
      queryBuilder({
        operators: ["contains", "is_not_empty"],
        defaultOperator: "contains",
      }),
    );

    expect(onApplyValue).not.toHaveBeenCalled();
  });

  it("does not apply a keyed query-builder chip before a key is chosen", () => {
    const { onApplyValue } = selectFromMenu(
      queryBuilder({
        defaultOperator: "is_not_empty",
        key: { placeholder: "key" },
      }),
    );

    expect(onApplyValue).not.toHaveBeenCalled();
  });
});
