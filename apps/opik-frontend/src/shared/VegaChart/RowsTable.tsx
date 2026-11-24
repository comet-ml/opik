import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ColumnDef, ColumnSizingState } from "@tanstack/react-table";

import { coerceNumericRows, VegaRows, VegaSpec } from "@/lib/charts/vega";
import { getTextWidth } from "@/lib/utils";
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

// The table's 14px text, measured at the header's medium weight so cells err wide.
const TEXT_FONT = "500 14px Inter";
const CELL_PADDING = 24;
const SORT_ICON_WIDTH = 18;
const MIN_COLUMN_WIDTH = 80;
const FALLBACK_CHAR_WIDTH = 8;

// Without a canvas to measure with (tests, locked-down browsers), the old rough 8px a character; it under-measures CJK.
const measureTexts = (texts: string[]): number[] => {
  try {
    return getTextWidth(texts, { font: TEXT_FONT });
  } catch {
    return texts.map((text) => [...text].length * FALLBACK_CHAR_WIDTH);
  }
};

// Widths measured before a web font loads use fallback metrics; each finished load measures them again.
const useFontsVersion = () => {
  const [version, setVersion] = useState(0);
  useEffect(() => {
    if (!document.fonts) return;
    const onLoaded = () => setVersion((v) => v + 1);
    document.fonts.addEventListener("loadingdone", onLoaded);
    return () => document.fonts.removeEventListener("loadingdone", onLoaded);
  }, []);
  return version;
};

// The width that shows a column's header and longest value in full.
const contentWidths = (keys: string[], rows: Row[]): ColumnSizingState => {
  const cells = keys.map((key) => rows.map((row) => formatCell(row[key])));
  const texts = [...new Set([...keys, ...cells.flat()])];
  const measured = measureTexts(texts);
  const widthOf = new Map(texts.map((text, i) => [text, measured[i]]));
  const textWidth = (text: string) =>
    Math.ceil(widthOf.get(text) ?? 0) + CELL_PADDING;

  return Object.fromEntries(
    keys.map((key, i) => [
      key,
      cells[i].reduce(
        (width, text) => Math.max(width, textWidth(text)),
        Math.max(MIN_COLUMN_WIDTH, textWidth(key) + SORT_ICON_WIDTH),
      ),
    ]),
  );
};

// Narrow columns keep their width and wide ones split the rest, never below the minimum; only then does the table scroll.
const shareWidth = (
  widths: ColumnSizingState,
  available: number,
): ColumnSizingState => {
  let remaining = available;
  let left = Object.keys(widths).length;
  return Object.fromEntries(
    Object.entries(widths)
      .sort(([, a], [, b]) => a - b)
      .map(([id, width]) => {
        const share = Math.floor(remaining / left);
        const shown = Math.min(width, Math.max(MIN_COLUMN_WIDTH, share));
        remaining -= shown;
        left -= 1;
        return [id, shown];
      }),
  );
};

// The default table frame, scrolling inside the widget with the header stuck to its top; it hugs a short table.
// Its own scrollbar styles keep the scrollbar visible where the OS hides it, so columns past the edge show.
// A stable gutter keeps the measured inner width when a vertical scrollbar comes and goes.
const RowsTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => (
  <div
    className="relative max-h-full overflow-auto rounded-md border [scrollbar-gutter:stable] [&::-webkit-scrollbar-thumb:hover]:bg-[var(--scrollbar-thumb-hover)] [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-[var(--scrollbar-thumb)] [&::-webkit-scrollbar]:size-2 [&::-webkit-scrollbar]:bg-transparent [&_thead[data-sticky-vertical]]:!top-0"
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
          minSize: MIN_COLUMN_WIDTH,
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

  // The scroll frame's inner width leaves out its border and scrollbar; 0 until measured.
  const [frameWidth, setFrameWidth] = useState(0);
  const { ref: frameRef } = useObserveResizeNode<HTMLDivElement>(
    useCallback(
      (node) =>
        setFrameWidth(
          node.querySelector<HTMLElement>("[data-table-scroll-container]")
            ?.clientWidth || node.clientWidth,
        ),
      [],
    ),
  );

  const fontsVersion = useFontsVersion();
  const limits = useMemo(
    () => contentWidths(keys, data),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- measure again after each font load
    [keys, data, fontsVersion],
  );
  // A column is never saved or drawn wider than its content or the widget, which another viewer's screen may make narrow,
  // nor narrower than the minimum.
  const capWidth = useCallback(
    (id: string, width: number) =>
      Math.max(
        MIN_COLUMN_WIDTH,
        Math.min(width, limits[id] ?? width, frameWidth || width),
      ),
    [limits, frameWidth],
  );

  // Columns the user sized keep their width; the others share what is left.
  const shownSizing = useMemo(() => {
    // Own keys only: a column may be named like an object property, such as "constructor".
    const sized = Object.fromEntries(
      keys
        .filter(
          (key) =>
            Object.hasOwn(columnSizing, key) &&
            typeof columnSizing[key] === "number",
        )
        .map((key) => [key, capWidth(key, columnSizing[key])]),
    );
    const used = Object.values(sized).reduce((sum, width) => sum + width, 0);
    const rest = Object.fromEntries(
      keys
        .filter((key) => !Object.hasOwn(sized, key))
        .map((key) => [key, limits[key]]),
    );
    return {
      ...sized,
      ...shareWidth(
        rest,
        frameWidth ? Math.max(0, frameWidth - used) : Infinity,
      ),
    };
  }, [keys, columnSizing, limits, frameWidth, capWidth]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: shownSizing,
      onColumnResize: ((updater) => {
        const next =
          typeof updater === "function" ? updater(shownSizing) : updater;
        // Only the dragged column changes; the others keep their saved widths, which may suit a wider screen.
        const dragged = Object.entries(next)
          .filter(([id, width]) => width !== shownSizing[id])
          .map(([id, width]) => [id, capWidth(id, width)]);
        const widths = { ...columnSizing, ...Object.fromEntries(dragged) };
        markWidthsPending(widths);
        setColumnSizing(widths);
      }) as OnChangeFn<ColumnSizingState>,
    }),
    [columnSizing, shownSizing, capWidth, markWidthsPending],
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
