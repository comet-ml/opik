import { describe, it, expect } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { RowSelectionState } from "@tanstack/react-table";

import { useRowSelectionChange } from "./useRowSelectionChange";

const toggle =
  (id: string, selected: boolean) =>
  (old: RowSelectionState): RowSelectionState => {
    const next = { ...old };
    if (selected) next[id] = true;
    else delete next[id];
    return next;
  };

const renderSelection = (initial: RowSelectionState, allSelected: boolean) =>
  renderHook(() => {
    const [rowSelection, setRowSelection] = useState(initial);
    const [isAllItemsSelected, setIsAllItemsSelected] = useState(allSelected);
    const onChange = useRowSelectionChange(
      rowSelection,
      setRowSelection,
      isAllItemsSelected,
      setIsAllItemsSelected,
    );
    return { rowSelection, isAllItemsSelected, onChange };
  });

describe("useRowSelectionChange", () => {
  it("applies every row of a shift-click range dispatched in one event", () => {
    const { result } = renderSelection({ a: true }, false);

    act(() => {
      ["b", "c", "d"].forEach((id) =>
        result.current.onChange(toggle(id, true)),
      );
    });

    expect(result.current.rowSelection).toEqual({
      a: true,
      b: true,
      c: true,
      d: true,
    });
  });

  it("drops the all-items selection when a range deselects rows", () => {
    const { result } = renderSelection({ a: true, b: true, c: true }, true);

    act(() => {
      ["b", "c"].forEach((id) => result.current.onChange(toggle(id, false)));
    });

    expect(result.current.rowSelection).toEqual({ a: true });
    expect(result.current.isAllItemsSelected).toBe(false);
  });
});
