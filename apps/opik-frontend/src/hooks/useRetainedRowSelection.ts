import {
  Dispatch,
  SetStateAction,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { RowSelectionState } from "@tanstack/react-table";
import { reconcileSelectedRows } from "@/lib/table";

type UseRetainedRowSelectionArgs<T extends { id: string }> = {
  rows: T[];
  /**
   * Caller-built selection scope. Include optional fields such as `type` when
   * the same table host mixes entity kinds (e.g. traces vs spans).
   */
  scope: Record<string, unknown>;
  /**
   * IDs removed from the authoritative dataset (e.g. after a successful
   * delete). Pruned from retained payloads and checkbox selection; off-page
   * rows that still exist must not be listed here.
   */
  deletedIds?: ReadonlySet<string>;
};

type UseRetainedRowSelectionResult<T extends { id: string }> = {
  rowSelection: RowSelectionState;
  setRowSelection: Dispatch<SetStateAction<RowSelectionState>>;
  selectedRows: T[];
  clearRowSelection: () => void;
};

/**
 * Owns table multi-select state and clears it when the selection scope changes
 * (project, filters, date range, optional type, etc.), while retaining selected
 * row payloads across paginated/refetched `rows` via reconcileSelectedRows.
 * Pass `deletedIds` (or call `clearRowSelection`) after deletes so stale IDs
 * cannot remain bulk-action targets.
 */
const useRetainedRowSelection = <T extends { id: string }>({
  rows,
  scope,
  deletedIds,
}: UseRetainedRowSelectionArgs<T>): UseRetainedRowSelectionResult<T> => {
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const selectedRowsMapRef = useRef(new Map<string, T>());
  const selectionScopeKeyRef = useRef<string | undefined>(undefined);
  const selectionScopeKey = JSON.stringify(scope);
  const deletedIdsKey = deletedIds?.size
    ? [...deletedIds].sort().join("\0")
    : "";

  const clearRowSelection = useCallback(() => {
    setRowSelection({});
    selectedRowsMapRef.current.clear();
  }, []);

  useEffect(() => {
    clearRowSelection();
  }, [selectionScopeKey, clearRowSelection]);

  // Drop checkbox state for IDs known deleted from the authoritative dataset.
  useEffect(() => {
    if (!deletedIds?.size) {
      return;
    }

    deletedIds.forEach((id) => {
      selectedRowsMapRef.current.delete(id);
    });

    setRowSelection((prev) => {
      let changed = false;
      const next: RowSelectionState = { ...prev };
      deletedIds.forEach((id) => {
        if (next[id]) {
          delete next[id];
          changed = true;
        }
      });
      return changed ? next : prev;
    });
  }, [deletedIds, deletedIdsKey]);

  const selectedRows = useMemo(
    () =>
      reconcileSelectedRows(selectedRowsMapRef.current, rowSelection, rows, {
        scopeKey: selectionScopeKey,
        scopeKeyRef: selectionScopeKeyRef,
        deletedIds,
      }),
    [rowSelection, rows, selectionScopeKey, deletedIds, deletedIdsKey],
  );

  return {
    rowSelection,
    setRowSelection,
    selectedRows,
    clearRowSelection,
  };
};

export default useRetainedRowSelection;
