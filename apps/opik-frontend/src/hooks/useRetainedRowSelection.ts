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
 */
const useRetainedRowSelection = <T extends { id: string }>({
  rows,
  scope,
}: UseRetainedRowSelectionArgs<T>): UseRetainedRowSelectionResult<T> => {
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const selectedRowsMapRef = useRef(new Map<string, T>());
  const selectionScopeKeyRef = useRef<string | undefined>(undefined);
  const selectionScopeKey = JSON.stringify(scope);

  const clearRowSelection = useCallback(() => {
    setRowSelection({});
    selectedRowsMapRef.current.clear();
  }, []);

  useEffect(() => {
    clearRowSelection();
  }, [selectionScopeKey, clearRowSelection]);

  const selectedRows = useMemo(
    () =>
      reconcileSelectedRows(selectedRowsMapRef.current, rowSelection, rows, {
        scopeKey: selectionScopeKey,
        scopeKeyRef: selectionScopeKeyRef,
      }),
    [rowSelection, rows, selectionScopeKey],
  );

  return {
    rowSelection,
    setRowSelection,
    selectedRows,
    clearRowSelection,
  };
};

export default useRetainedRowSelection;
