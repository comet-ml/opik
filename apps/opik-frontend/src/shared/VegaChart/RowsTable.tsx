import React, { useEffect, useMemo, useRef, useState } from "react";
import { ColumnDef, ColumnSizingState } from "@tanstack/react-table";

import { coerceNumericRows, VegaRows, VegaSpec } from "@/lib/charts/vega";
import { Sorting } from "@/types/sorting";
import { COLUMN_TYPE, OnChangeFn } from "@/types/shared";
import DataTable from "@/shared/DataTable/DataTable";
import { DataTableWrapperProps } from "@/shared/DataTable/DataTableWrapper";
import TypeHeader from "@/shared/DataTableHeaders/TypeHeader";
import TextCell from "@/shared/DataTableCells/TextCell";

/** A table "chart" is stored as `{"table": {"columns": [...]}}` in place of a Vega-Lite spec. */
export const tableColumns = (spec: VegaSpec): string[] | null => {
  const table = (spec as { table?: { columns?: unknown } }).table;
  return table && Array.isArray(table.columns)
    ? table.columns.map(String)
    : null;
};

type Row = Record<string, unknown>;

const isEmpty = (value: unknown) =>
  value === null || value === undefined || value === "";

const toText = (value: unknown): string =>
  typeof value === "object" ? JSON.stringify(value) : String(value);

const formatCell = (value: unknown): string => {
  if (isEmpty(value)) return "—";
  if (typeof value === "number") {
    return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
  return toText(value);
};

// Numbers before text so a mostly numeric column still ranks; empty cells always go last.
const compareCells = (x: unknown, y: unknown): number => {
  if (typeof x === "number" && typeof y === "number") return x - y;
  if (typeof x === "number") return -1;
  if (typeof y === "number") return 1;
  return toText(x).localeCompare(toText(y), undefined, {
    numeric: true,
    sensitivity: "base",
  });
};

const sortRows = (rows: Row[], sorting: Sorting): Row[] => {
  const [sort] = sorting;
  if (!sort) return rows;
  const direction = sort.desc ? -1 : 1;
  return [...rows].sort((a, b) => {
    const x = a[sort.id];
    const y = b[sort.id];
    if (isEmpty(x) || isEmpty(y))
      return Number(isEmpty(x)) - Number(isEmpty(y));
    return direction * compareCells(x, y);
  });
};

// The default table frame, scrolling inside the widget with the header stuck to its top; it hugs a short table.
const RowsTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => (
  <div className="relative max-h-full overflow-auto rounded-md border [&_thead[data-sticky-vertical]]:!top-0">
    {children}
  </div>
);

export type RowsTableState = {
  sorting?: Sorting;
  columnsWidth?: ColumnSizingState;
  onSortingChange?: (sorting: Sorting) => void;
  onColumnsWidthChange?: (columnsWidth: ColumnSizingState) => void;
};

type RowsTableProps = {
  rows: VegaRows;
  columns?: string[] | null;
  note?: string;
} & RowsTableState;

const RowsTable: React.FunctionComponent<RowsTableProps> = ({
  rows,
  columns,
  note,
  sorting: savedSorting,
  columnsWidth: savedColumnsWidth,
  onSortingChange,
  onColumnsWidthChange,
}) => {
  // A widget's rows come straight from the query, where numbers are often strings.
  const data = useMemo(() => coerceNumericRows(rows), [rows]);
  const keys = useMemo(
    () => (columns?.length ? columns : Object.keys(data[0] ?? {})),
    [columns, data],
  );

  const columnDefs = useMemo<ColumnDef<Row>[]>(
    () =>
      keys.map((key) => {
        const numeric = data.some((row) => typeof row[key] === "number");
        return {
          id: key,
          accessorFn: (row) => formatCell(row[key]),
          header: TypeHeader,
          cell: TextCell as ColumnDef<Row>["cell"],
          meta: {
            type: numeric ? COLUMN_TYPE.number : COLUMN_TYPE.string,
            header: key,
          },
          // Rankings read best first.
          sortDescFirst: numeric,
        };
      }),
    [keys, data],
  );

  // Local state keeps sorting and resizing usable where they can't be saved (read-only, chat preview).
  const [sorting, setSorting] = useState<Sorting>(savedSorting ?? []);
  const [columnSizing, setColumnSizing] = useState<ColumnSizingState>(
    savedColumnsWidth ?? {},
  );
  useEffect(() => setSorting(savedSorting ?? []), [savedSorting]);
  useEffect(
    () => setColumnSizing(savedColumnsWidth ?? {}),
    [savedColumnsWidth],
  );

  // A saved sort can name a column the query no longer returns.
  const activeSorting = useMemo(
    () => sorting.filter((sort) => keys.includes(sort.id)),
    [sorting, keys],
  );
  const sortedData = useMemo(
    () => sortRows(data, activeSorting),
    [data, activeSorting],
  );

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: activeSorting,
      setSorting: ((updater) => {
        const next =
          typeof updater === "function" ? updater(activeSorting) : updater;
        setSorting(next);
        onSortingChange?.(next);
      }) as OnChangeFn<Sorting>,
    }),
    [activeSorting, onSortingChange],
  );

  // Widths change on every mouse move; they are saved once, when the drag ends.
  const pendingWidthsRef = useRef<ColumnSizingState | null>(null);
  const onColumnsWidthChangeRef = useRef(onColumnsWidthChange);
  onColumnsWidthChangeRef.current = onColumnsWidthChange;
  useEffect(() => {
    const flush = () => {
      const widths = pendingWidthsRef.current;
      if (!widths) return;
      pendingWidthsRef.current = null;
      onColumnsWidthChangeRef.current?.(widths);
    };
    // After the table's own drag-end handler, which sets the final width.
    const onDragEnd = () => setTimeout(flush);
    document.addEventListener("mouseup", onDragEnd);
    document.addEventListener("touchend", onDragEnd);
    return () => {
      document.removeEventListener("mouseup", onDragEnd);
      document.removeEventListener("touchend", onDragEnd);
    };
  }, []);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing,
      onColumnResize: ((updater) => {
        const next =
          typeof updater === "function" ? updater(columnSizing) : updater;
        pendingWidthsRef.current = next;
        setColumnSizing(next);
      }) as OnChangeFn<ColumnSizingState>,
    }),
    [columnSizing],
  );

  return (
    <div className="flex size-full flex-col gap-1">
      {note && <span className="comet-body-xs text-muted-slate">{note}</span>}
      {/* DataTable's root takes the height, so the frame can cap at it and scroll. */}
      <div className="min-h-0 flex-1 [&>div]:h-full">
        <DataTable
          columns={columnDefs}
          data={sortedData}
          sortConfig={sortConfig}
          resizeConfig={resizeConfig}
          TableWrapper={RowsTableWrapper}
          stickyHeader
        />
      </div>
    </div>
  );
};

export default RowsTable;
