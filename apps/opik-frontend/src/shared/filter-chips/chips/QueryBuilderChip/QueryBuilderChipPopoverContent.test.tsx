import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { Popover } from "@/ui/popover";
import { COLUMN_TYPE } from "@/types/shared";
import { FilterOperator } from "@/types/filters";
import {
  QueryBuilderChipDefinition,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";
import QueryBuilderChipPopoverContent from "./QueryBuilderChipPopoverContent";

const definition = (
  operators: FilterOperator[],
  defaultOperator: FilterOperator,
): QueryBuilderChipDefinition => ({
  id: "comments",
  field: "comments",
  label: "Comments",
  kind: "query-builder",
  columnType: COLUMN_TYPE.list,
  operators,
  defaultOperator,
});

const renderPopover = (
  def: QueryBuilderChipDefinition,
  value?: QueryBuilderChipValue,
) => {
  const onApply = vi.fn();
  render(
    <Popover open>
      <QueryBuilderChipPopoverContent
        definition={def}
        value={value}
        onApply={onApply}
        onClear={vi.fn()}
      />
    </Popover>,
  );
  return onApply;
};

describe("QueryBuilderChipPopoverContent", () => {
  it("applies a default operator that takes no value as soon as it opens", () => {
    const onApply = renderPopover(
      definition(["is_not_empty", "is_empty"], "is_not_empty"),
    );

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][0].rows).toEqual([
      expect.objectContaining({ operator: "is_not_empty", value: "" }),
    ]);
  });

  it("does not apply a default operator that needs a value", () => {
    const onApply = renderPopover(
      definition(["contains", "is_not_empty"], "contains"),
    );

    expect(onApply).not.toHaveBeenCalled();
  });

  it("does not re-apply when the chip already has rows", () => {
    const onApply = renderPopover(
      definition(["is_not_empty", "is_empty"], "is_not_empty"),
      {
        rows: [
          {
            id: "row-1",
            field: "comments",
            type: COLUMN_TYPE.list,
            operator: "is_empty",
            key: "",
            value: "",
          },
        ],
      },
    );

    expect(onApply).not.toHaveBeenCalled();
  });
});
