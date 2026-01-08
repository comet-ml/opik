import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

interface UseVisibleItemsByWidthConfig {
  itemGap?: number;
  counterWidth?: number;
  containerPadding?: number;
  minFirstItemWidth?: number;
}

interface UseVisibleItemsByWidthReturn<T> {
  cellRef: (element: HTMLDivElement) => void;
  visibleCount: number;
  remainingCount: number;
  visibleItems: T[];
  hiddenItems: T[];
  hasHiddenItems: boolean;
  onMeasure: (widths: number[]) => void;
}

const DEFAULT_ITEM_GAP = 6;
const DEFAULT_COUNTER_WIDTH = 30;
const DEFAULT_CONTAINER_PADDING = 12;
const DEFAULT_MIN_FIRST_ITEM_WIDTH = 70;

const calculateVisibleCount = (
  cellWidth: number,
  itemWidths: number[],
  totalItemCount: number,
  itemGap: number,
  counterWidth: number,
  containerPadding: number,
  minFirstItemWidth: number,
): number => {
  if (totalItemCount === 0 || itemWidths.length === 0 || cellWidth === 0) {
    return 0;
  }

  const itemListWidth = itemWidths.reduce((acc, w) => acc + w + itemGap, 0);
  const containerWidth = cellWidth - containerPadding;

  if (containerWidth >= itemListWidth) {
    return totalItemCount;
  }

  const lastIdx = itemWidths.length - 1;
  const counterTotalWidth = counterWidth + itemGap;
  const firstItemWidth = itemWidths[0] ?? 0;
  const minFirstWidth =
    Math.max(firstItemWidth / 2, minFirstItemWidth) + counterTotalWidth;

  const isSingleItem = itemWidths.length === 1;
  const isNarrowContainer =
    itemWidths.length >= 2 &&
    containerWidth < firstItemWidth + itemWidths[1] + itemGap * 2;

  // for single item or narrow container, show at least 1 item if it fits
  if (isSingleItem || isNarrowContainer) {
    if (containerWidth >= minFirstWidth) {
      return 1;
    }
    return 0;
  }

  let availableWidth = containerWidth - counterTotalWidth;

  for (let idx = 0; idx <= lastIdx; idx++) {
    const nextItemWidth =
      itemWidths[idx] + (idx > 0 && idx < lastIdx ? itemGap * 2 : 0);

    availableWidth -= nextItemWidth;

    if (availableWidth < 0) {
      return idx;
    }
  }

  return totalItemCount;
};

export function useVisibleItemsByWidth<T>(
  items: T[],
  config?: UseVisibleItemsByWidthConfig,
): UseVisibleItemsByWidthReturn<T> {
  const {
    itemGap = DEFAULT_ITEM_GAP,
    counterWidth = DEFAULT_COUNTER_WIDTH,
    containerPadding = DEFAULT_CONTAINER_PADDING,
    minFirstItemWidth = DEFAULT_MIN_FIRST_ITEM_WIDTH,
  } = config ?? {};

  const [visibleCount, setVisibleCount] = useState(0);
  const widthListRef = useRef<number[]>([]);
  const cellWidthRef = useRef<number>(0);

  const recalculate = useCallback(() => {
    const count = calculateVisibleCount(
      cellWidthRef.current,
      widthListRef.current,
      items.length,
      itemGap,
      counterWidth,
      containerPadding,
      minFirstItemWidth,
    );
    setVisibleCount((prev) => (prev !== count ? count : prev));
  }, [
    items.length,
    itemGap,
    counterWidth,
    containerPadding,
    minFirstItemWidth,
  ]);

  const handleResize = useCallback(
    (node: HTMLDivElement) => {
      cellWidthRef.current = node.clientWidth;
      recalculate();
    },
    [recalculate],
  );

  const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>(handleResize);

  const onMeasure = useCallback(
    (measureList: number[]) => {
      widthListRef.current = measureList;
      recalculate();
    },
    [recalculate],
  );

  // recalculate when items length changes
  useEffect(() => {
    if (cellWidthRef.current > 0) {
      recalculate();
    }
  }, [items.length, recalculate]);

  const derivedValues = useMemo(() => {
    const remaining = items.length - visibleCount;
    return {
      remainingCount: remaining,
      visibleItems: items.slice(0, visibleCount),
      hiddenItems: items.slice(visibleCount),
      hasHiddenItems: remaining > 0,
    };
  }, [items, visibleCount]);

  return {
    cellRef,
    visibleCount,
    remainingCount: derivedValues.remainingCount,
    visibleItems: derivedValues.visibleItems,
    hiddenItems: derivedValues.hiddenItems,
    hasHiddenItems: derivedValues.hasHiddenItems,
    onMeasure,
  };
}
