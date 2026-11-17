import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ColumnDef, ColumnSizingState } from "@tanstack/react-table";

import { coerceNumericRows, VegaRows, VegaSpec } from "@/lib/charts/vega";
import { Sorting } from "@/types/sorting";
import { COLUMN_TYPE, OnChangeFn } from "@/types/shared";
import DataTable from "@/shared/DataTable/DataTable";
import { DataTableWrapperProps } from "@/shared/DataTable/DataTableWrapper";
import TypeHeader from "@/shared/DataTableHeaders/TypeHeader";
import TextCell from "@/shared/DataTableCells/TextCell";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

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
  if (isEmpty(value)) return "-";
  if (typeof value === "number") {
    return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
  return toText(value);
};

const compareSameKind = (x: unknown, y: unknown): number =>
  typeof x === "number" && typeof y === "number"
    ? x - y
    : toText(x).localeCompare(toText(y), undefined, {
        numeric: true,
        sensitivity: "base",
      });

// Either direction keeps numbers, then text, then empty cells, so a mostly numeric column still ranks.
const kindRank = (value: unknown) =>
  isEmpty(value) ? 2 : typeof value === "number" ? 0 : 1;

const sortRows = (rows: Row[], sorting: Sorting): Row[] => {
  const [sort] = sorting;
  if (!sort) return rows;
  const direction = sort.desc ? -1 : 1;
  return [...rows].sort((a, b) => {
    const x = a[sort.id];
    const y = b[sort.id];
    const byKind = kindRank(x) - kindRank(y);
    return byKind || direction * compareSameKind(x, y);
  });
};

// Widths change on every mouse move; they are saved once, when the drag ends.
const usePersistColumnWidths = (
  onChange?: (columnsWidth: ColumnSizingState) => void,
) => {
  const pendingRef = useRef<ColumnSizingState | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const flush = () => {
      const widths = pendingRef.current;
      if (!widths) return;
      pendingRef.current = null;
      onChangeRef.current?.(widths);
    };
    // After the table's own drag-end handler, which sets the final width.
    const onDragEnd = () => {
      clearTimeout(timer);
      timer = setTimeout(flush);
    };
    document.addEventListener("mouseup", onDragEnd);
    document.addEventListener("touchend", onDragEnd);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("mouseup", onDragEnd);
      document.removeEventListener("touchend", onDragEnd);
    };
  }, []);

  return useCallback((widths: ColumnSizingState) => {
    pendingRef.current = widths;
  }, []);
};

// A column is never saved or drawn wider than the widget, which another viewer's screen may make narrow.
const capWidths = (
  widths: ColumnSizingState,
  maxWidth: number,
): ColumnSizingState =>
  maxWidth > 0
    ? Object.fromEntries(
        Object.entries(widths).map(([id, width]) => [
          id,
          Math.min(width, maxWidth),
        ]),
      )
    : widths;

// The default table frame, scrolling inside the widget with the header stuck to its top; it hugs a short table.
// Its own scrollbar styles keep the scrollbar visible where the OS hides it, so columns past the edge show.
const RowsTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => (
  <div
    className="relative max-h-full overflow-auto rounded-md border [&::-webkit-scrollbar-thumb:hover]:bg-[var(--scrollbar-thumb-hover)] [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-[var(--scrollbar-thumb)] [&::-webkit-scrollbar]:size-2 [&::-webkit-scrollbar]:bg-transparent [&_thead[data-sticky-vertical]]:!top-0"
    data-table-scroll-container
  >
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

  const markWidthsPending = usePersistColumnWidths(onColumnsWidthChange);

  const [frameWidth, setFrameWidth] = useState(0);
  const { ref: frameRef } = useObserveResizeNode<HTMLDivElement>(
    useCallback((node) => setFrameWidth(node.clientWidth), []),
  );

  // Widening the widget brings a capped column back to its saved width.
  const shownSizing = useMemo(
    () => capWidths(columnSizing, frameWidth),
    [columnSizing, frameWidth],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: shownSizing,
      onColumnResize: ((updater) => {
        const next =
          typeof updater === "function" ? updater(shownSizing) : updater;
        // Only the dragged column changes; the others keep their saved widths, which may suit a wider screen.
        const dragged = Object.fromEntries(
          Object.entries(next).filter(
            ([id, width]) => width !== shownSizing[id],
          ),
        );
        const widths = {
          ...columnSizing,
          ...capWidths(dragged, frameWidth),
        };
        markWidthsPending(widths);
        setColumnSizing(widths);
      }) as OnChangeFn<ColumnSizingState>,
    }),
    [columnSizing, shownSizing, frameWidth, markWidthsPending],
  );

  return (
    <div className="flex size-full flex-col gap-1">
      {note && <span className="comet-body-xs text-muted-slate">{note}</span>}
      {/* DataTable's root takes the height, so the frame can cap at it and scroll. */}
      <div ref={frameRef} className="min-h-0 flex-1 [&>div]:h-full">
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
