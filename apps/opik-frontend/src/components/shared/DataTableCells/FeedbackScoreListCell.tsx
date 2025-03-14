import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import FeedbackScoreHoverCard from "../FeedbackScoreTag/FeedbackScoreHoverCard";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import ChildrenWidthMeasurer from "../ChildrenWidthMeasurer/ChildrenWidthMeasurer";

const TAG_GAP = 6;
const COUNTER_WIDTH = 30;

type CustomMeta<TData> = {
  getHoverCardName: (row: TData) => string;
  isAverageScores?: boolean;
};

const calculateVisibleCount = (
  cellWidth: number,
  tagWidths: number[],
  sortedListLength: number,
): number => {
  const tagListWidth = tagWidths.reduce((acc, w) => acc + w + TAG_GAP, 0);
  const containerWidth = cellWidth - 12;

  if (containerWidth > tagListWidth) {
    return sortedListLength;
  }

  const lastIdx = tagWidths.length - 1;
  const counterWidth = COUNTER_WIDTH + TAG_GAP;
  const minFirstTagWidth = Math.max(tagWidths[0] / 2, 70) + counterWidth;

  const isSingleTag = tagWidths.length === 1;
  const isNarrowContainer =
    tagWidths.length >= 2 &&
    containerWidth < tagWidths[0] + tagWidths[1] + TAG_GAP * 2;

  if (isSingleTag || isNarrowContainer) {
    if (containerWidth >= minFirstTagWidth) {
      return 1;
    }
  }

  let availableWidth = containerWidth - counterWidth;

  for (let idx = 0; idx <= lastIdx; idx++) {
    const nextItemWidth =
      tagWidths[idx] + (idx > 0 && idx < lastIdx ? TAG_GAP * 2 : 0);

    availableWidth -= nextItemWidth;

    if (availableWidth < 0) {
      return idx;
    }
  }

  return sortedListLength;
};

const FeedbackScoreListCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const feedbackScoreList = context.getValue() as TraceFeedbackScore[];
  const { getHoverCardName, isAverageScores } = (context.column.columnDef.meta
    ?.custom ?? {}) as CustomMeta<TData>;

  const hoverCardName = getHoverCardName(context.row.original);
  const isEmpty = !feedbackScoreList?.length;
  const [visibleCount, setVisibleCount] = useState(0);
  const widthList = useRef<number[]>([]);
  const remainingCount = feedbackScoreList.length - visibleCount;

  const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>((node) => {
    const visibleCount = calculateVisibleCount(
      node.clientWidth,
      widthList.current,
      sortedList.length,
    );
    setVisibleCount(visibleCount);
  });

  const sortedList = feedbackScoreList.sort((c1, c2) =>
    c1.name.localeCompare(c2.name),
  );

  const onMeasure = useCallback((measureList: number[]) => {
    widthList.current = measureList;
  }, []);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="p-0 py-1"
    >
      {isEmpty ? (
        "-"
      ) : (
        <div ref={cellRef} className="w-full min-w-0 flex-1 overflow-hidden">
          <FeedbackScoreHoverCard
            name={hoverCardName}
            isAverageScores={isAverageScores}
            tagList={sortedList}
            hidden={!remainingCount}
          >
            <div className="flex size-full items-center justify-start gap-1.5 overflow-hidden p-0 py-1 pr-2">
              <ChildrenWidthMeasurer onMeasure={onMeasure}>
                {sortedList.map<React.ReactNode>((item) => (
                  <div key={item.name}>
                    <FeedbackScoreTag
                      label={item.name}
                      value={item.value}
                      reason={item.reason}
                    />
                  </div>
                ))}
              </ChildrenWidthMeasurer>
              {sortedList
                .slice(0, visibleCount)
                .map<React.ReactNode>((item) => (
                  <FeedbackScoreTag
                    key={item.name}
                    label={item.name}
                    value={item.value}
                    className="min-w-0"
                  />
                ))}
              {Boolean(remainingCount) && (
                <div className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate">
                  +{remainingCount}
                </div>
              )}
            </div>
          </FeedbackScoreHoverCard>
        </div>
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreListCell;
