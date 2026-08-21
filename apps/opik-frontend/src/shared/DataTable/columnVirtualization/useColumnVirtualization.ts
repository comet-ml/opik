import { useEffect, useMemo } from "react";
import { Column, Table } from "@tanstack/react-table";
import { useVirtualizer } from "@tanstack/react-virtual";
import isFunction from "lodash/isFunction";

import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import { observeHorizontalOffset } from "@/shared/DataTable/virtualizerOptions";
import {
  ColumnWindow,
  INACTIVE_COLUMN_WINDOW,
} from "@/shared/DataTable/columnVirtualization/columnWindow";

// Windowing only pays for itself once a table is actually wide
const DEFAULT_MIN_COLUMNS = 50;
const DEFAULT_OVERSCAN_COLUMNS = 3;

const EMPTY_LAYOUT = {
  centerColumns: [] as Column<never>[],
  leftCount: 0,
  centerCount: 0,
  leftWidth: 0,
  rightWidth: 0,
  centerWidth: 0,
};

export type ColumnVirtualizationContext = {
  columnCount: number;
  rowCount: number;
};

export type ColumnVirtualizationConfig = {
  enabled?: boolean | ((context: ColumnVirtualizationContext) => boolean);
  minColumns?: number;
  overscan?: number;
  // For tables that scroll in their own container instead of the page body.
  // Resolve it from state, not from a bare ref, or windowing stays off until
  // something else triggers a render.
  getScrollElement?: () => HTMLElement | null;
};

const useColumnVirtualization = <TData>(
  table: Table<TData>,
  config?: ColumnVirtualizationConfig,
  { supported = true }: { supported?: boolean } = {},
): ColumnWindow => {
  const { scrollContainer: pageBodyScrollContainer } =
    usePageBodyScrollContainer();
  const visibleColumns = table.getVisibleLeafColumns();
  const columnSizing = table.getState().columnSizing;

  const {
    enabled: enabledOption = false,
    minColumns = DEFAULT_MIN_COLUMNS,
    overscan = DEFAULT_OVERSCAN_COLUMNS,
    getScrollElement,
  } = config ?? {};

  const scrollElement = getScrollElement
    ? getScrollElement()
    : pageBodyScrollContainer;

  // Headers that span several columns, and grouped rows that render one cell per
  // group, both stop matching a windowed colgroup
  const windowable =
    supported &&
    table.getHeaderGroups().length === 1 &&
    table.getState().grouping.length === 0;

  const optedIn = isFunction(enabledOption)
    ? enabledOption({
        columnCount: visibleColumns.length,
        rowCount: table.getRowModel().rows.length,
      })
    : enabledOption;

  // Without a scroll container there is no viewport to window against
  const enabled =
    windowable &&
    optedIn &&
    Boolean(scrollElement) &&
    visibleColumns.length >= minColumns;

  const layout = useMemo(() => {
    if (!enabled) return EMPTY_LAYOUT;

    const width = (columns: { getSize: () => number }[]) =>
      columns.reduce((sum, column) => sum + column.getSize(), 0);

    const centerColumns = table.getCenterVisibleLeafColumns();

    return {
      centerColumns,
      leftCount: table.getLeftVisibleLeafColumns().length,
      centerCount: centerColumns.length,
      leftWidth: width(table.getLeftVisibleLeafColumns()),
      rightWidth: width(table.getRightVisibleLeafColumns()),
      centerWidth: width(centerColumns),
    };
    // table is stable; the two state slices below are what actually move
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, table, visibleColumns, columnSizing]);

  // Only the center columns are windowed; the pinned widths become padding so
  // the offsets stay in the same coordinate space as scrollLeft
  // A disabled virtualizer resolves no scroll element, so a table that has not
  // opted in attaches no listeners and measures nothing
  const virtualizer = useVirtualizer({
    enabled,
    horizontal: true,
    count: layout.centerCount,
    getScrollElement: () => scrollElement,
    initialOffset: () => scrollElement?.scrollLeft ?? 0,
    initialRect: {
      width: scrollElement?.clientWidth ?? 0,
      height: scrollElement?.clientHeight ?? 0,
    },
    estimateSize: (index) => layout.centerColumns[index].getSize(),
    getItemKey: (index) => layout.centerColumns[index].id,
    paddingStart: layout.leftWidth,
    paddingEnd: layout.rightWidth,
    overscan,
    observeElementOffset: observeHorizontalOffset,
  });

  const virtualColumns = virtualizer.getVirtualItems();
  const { measure } = virtualizer;

  useEffect(() => {
    measure();
  }, [measure, layout]);

  return useMemo(() => {
    if (!enabled || virtualColumns.length === 0) return INACTIVE_COLUMN_WINDOW;

    const first = virtualColumns[0];
    const last = virtualColumns[virtualColumns.length - 1];

    return {
      active: true,
      leftCount: layout.leftCount,
      centerCount: layout.centerCount,
      start: first.index,
      end: last.index,
      leadingWidth: Math.max(first.start - layout.leftWidth, 0),
      trailingWidth: Math.max(
        layout.leftWidth + layout.centerWidth - (last.start + last.size),
        0,
      ),
    };
  }, [enabled, layout, virtualColumns]);
};

export default useColumnVirtualization;
