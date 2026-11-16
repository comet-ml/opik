import { Dispatch, SetStateAction, useCallback } from "react";
import { RowSelectionState } from "@tanstack/react-table";

type SetRowSelection = Dispatch<SetStateAction<RowSelectionState>>;

export const useRowSelectionChange = (
  rowSelection: RowSelectionState,
  setRowSelection: SetRowSelection,
  isAllItemsSelected: boolean,
  setIsAllItemsSelected: (value: boolean) => void,
): SetRowSelection =>
  useCallback(
    (updaterOrValue) => {
      // Queued as-is: shift-click calls this once per row in the range, so each update must build on the previous one.
      setRowSelection(updaterOrValue);

      // With every item selected, all rows are selected and a range toggles them one way, so any shrink
      // against the rendered selection means the user deselected something.
      if (isAllItemsSelected) {
        const next =
          typeof updaterOrValue === "function"
            ? updaterOrValue(rowSelection)
            : updaterOrValue;

        if (Object.keys(next).length < Object.keys(rowSelection).length) {
          setIsAllItemsSelected(false);
        }
      }
    },
    [rowSelection, setRowSelection, isAllItemsSelected, setIsAllItemsSelected],
  );
