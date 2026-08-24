import { useCallback, useEffect, useMemo } from "react";
import { Column, Table } from "@tanstack/react-table";
import { useVirtualizer } from "@tanstack/react-virtual";

import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import { observeOwnAxisOffset } from "@/shared/DataTable/virtualizerOptions";
import {
  ColumnWindow,
  INACTIVE_COLUMN_WINDOW,
} from "@/shared/DataTable/columnVirtualization/columnWindow";

const DEFAULT_OVERSCAN_COLUMNS = 3;

const EMPTY_LAYOUT = {
  centerColumns: [] as Column<never>[],
  leftCount: 0,
  centerCount: 0,
  leftWidth: 0,
  rightWidth: 0,
  centerWidth: 0,
};

export type ColumnVirtualizationConfig = {
  enabled?: boolean;
  overscan?: number;
};

const useColumnVirtualization = <TData>(
  table: Table<TData>,
  config?: ColumnVirtualizationConfig,
  { supported = true }: { supported?: boolean } = {},
): ColumnWindow => {
  const { scrollContainer, horizontalScrollContainer } =
    usePageBodyScrollContainer();
  const visibleColumns = table.getVisibleLeafColumns();
  const columnSizing = table.getState().columnSizing;

  const { enabled: optedIn = false, overscan = DEFAULT_OVERSCAN_COLUMNS } =
    config ?? {};

  const scrollElement = horizontalScrollContainer ?? scrollContainer;

  const windowable = supported && table.getState().grouping.length === 0;

  const enabled = windowable && optedIn && Boolean(scrollElement);

  const layout = useMemo(() => {
    if (!enabled) return EMPTY_LAYOUT;

    const centerColumns = table.getCenterVisibleLeafColumns();

    return {
      centerColumns,
      leftCount: table.getLeftVisibleLeafColumns().length,
      centerCount: centerColumns.length,
      leftWidth: table.getLeftTotalSize(),
      rightWidth: table.getRightTotalSize(),
      centerWidth: table.getCenterTotalSize(),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, table, visibleColumns, columnSizing]);

  const getItemKey = useCallback(
    (index: number) => layout.centerColumns[index]?.id ?? index,
    [layout],
  );

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
    getItemKey,
    paddingStart: layout.leftWidth,
    paddingEnd: layout.rightWidth,
    overscan,
    observeElementOffset: observeOwnAxisOffset,
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
