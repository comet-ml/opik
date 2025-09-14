import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import isArray from "lodash/isArray";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import ChildrenWidthMeasurer from "../ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

const TAG_GAP = 6;
const COUNTER_WIDTH = 30;

type CustomMeta<TData> = {
  getHoverCardName: (row: TData) => string;
};

const calculateVisibleCount = (
  cellWidth: number,
  tagWidths: number[],
  sortedListLength: number,
) => {
  if (cellWidth <= 0 || tagWidths.length === 0) {
    return 0;
  }

  let visibleCount = 0;
  let totalWidth = 0;

  for (let i = 0; i < tagWidths.length; i++) {
    const tagWidth = tagWidths[i];
    const nextWidth = totalWidth + tagWidth + (visibleCount > 0 ? TAG_GAP : 0);

    if (nextWidth > cellWidth) {
      break;
    }

    totalWidth = nextWidth;
    visibleCount++;
  }

  // If we can fit all tags, show them all
  if (visibleCount === sortedListLength) {
    return sortedListLength;
  }

  // If we can't fit any tags, show 0
  if (visibleCount === 0) {
    return 0;
  }

  // Check if we can fit the remaining count indicator
  const remainingCountWidth = COUNTER_WIDTH + (visibleCount > 0 ? TAG_GAP : 0);

  if (totalWidth + remainingCountWidth <= cellWidth) {
    return visibleCount;
  }

  // If we can't fit the remaining count, reduce visible count by 1
  return Math.max(0, visibleCount - 1);
};

const FeedbackDefinitionListCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const feedbackDefinitionList = context.getValue() as string[];
  const { getHoverCardName } = (context.column.columnDef.meta?.custom ??
    {}) as CustomMeta<TData>;

  const hoverCardName = getHoverCardName(context.row.original);
  const isEmpty = !feedbackDefinitionList?.length;
  const [visibleCount, setVisibleCount] = useState(0);
  const widthList = useRef<number[]>([]);
  const remainingCount = feedbackDefinitionList.length - visibleCount;

  const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>((node) => {
    const visibleCount = calculateVisibleCount(
      node.clientWidth,
      widthList.current,
      sortedList.length,
    );
    setVisibleCount(visibleCount);
  });

  const sortedList = feedbackDefinitionList.sort((a, b) => a.localeCompare(b));

  const onMeasure = useCallback((measureList: number[]) => {
    widthList.current = measureList;
  }, []);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-1 pr-1"
    >
      {isEmpty ? (
        "-"
      ) : (
        <div ref={cellRef} className="w-full min-w-0 flex-1 overflow-hidden">
          <div className="flex min-w-0 flex-1">
            <div className="flex size-full items-center justify-start gap-1.5 overflow-hidden p-0 py-1 pr-2">
              <ChildrenWidthMeasurer onMeasure={onMeasure}>
                {sortedList.map<React.ReactNode>((item) => (
                  <div key={item}>
                    <ColoredTagNew label={item} />
                  </div>
                ))}
              </ChildrenWidthMeasurer>
              {sortedList
                .slice(0, visibleCount)
                .map<React.ReactNode>((item) => (
                  <ColoredTagNew key={item} label={item} className="min-w-0" />
                ))}
              {Boolean(remainingCount) && (
                <div className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate">
                  +{remainingCount}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </CellWrapper>
  );
};

type AggregationCustomMeta = {
  aggregationKey?: string;
};

const FeedbackDefinitionListAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey } = (custom ?? {}) as AggregationCustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue =
    (data as Record<string, unknown>)[aggregationKey ?? ""] ?? undefined;
  let value = "";

  if (isArray(rawValue)) {
    value = (rawValue as string[]).join(", ");
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={value}>
        <span className="truncate text-light-slate">{value}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

FeedbackDefinitionListCell.Aggregation = FeedbackDefinitionListAggregationCell;

export default FeedbackDefinitionListCell;

