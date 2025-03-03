import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import FeedbackScoreTagMeasurer from "../FeedbackScoreTag/FeedbackScoreTagMeasurer";
import FeedbackScoreHoverCard from "../FeedbackScoreTag/FeedbackScoreHoverCard";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

const TAG_GAP = 6;

type CustomMeta<TData> = {
  getHoverCardName: (row: TData) => string;
  isAverageScores?: boolean;
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

  const calcVisibleCount = (cellWidth: number) => {
    const tagListWidth = widthList.current.reduce(
      (acc, w) => acc + w + TAG_GAP,
      0,
    );

    if (cellWidth > tagListWidth) {
      setVisibleCount(sortedList.length);
      return;
    }

    const lastIdx = widthList.current.length - 1;
    const remainingWidth = 40;

    let availableWidth =
      cellWidth - remainingWidth - (remainingWidth ? TAG_GAP : 0);

    for (let idx = 0; idx <= lastIdx; idx++) {
      availableWidth -=
        widthList.current[idx] + (idx > 0 && idx < lastIdx ? TAG_GAP * 2 : 0);

      if (availableWidth < 0) {
        setVisibleCount(idx);
        return;
      }
    }
  };

  const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>((node) => {
    calcVisibleCount(node.clientWidth);
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
      <div ref={cellRef} className="w-full min-w-0 flex-1 overflow-hidden">
        {isEmpty ? (
          "-"
        ) : (
          <FeedbackScoreHoverCard
            name={hoverCardName}
            isAverageScores={isAverageScores}
            tagList={sortedList}
            hidden={!remainingCount}
          >
            <div className="flex size-full items-center justify-start gap-1.5 overflow-hidden p-0 py-1">
              <FeedbackScoreTagMeasurer
                onMeasure={onMeasure}
                tagList={sortedList}
              />
              {sortedList
                .slice(0, visibleCount)
                .map<React.ReactNode>((item) => (
                  <FeedbackScoreTag
                    key={item.name}
                    label={item.name}
                    value={item.value}
                    reason={item.reason}
                  />
                ))}
              {Boolean(remainingCount) && (
                <div className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate">
                  +{remainingCount}
                </div>
              )}
            </div>
          </FeedbackScoreHoverCard>
        )}
      </div>
    </CellWrapper>
  );
};

export default FeedbackScoreListCell;
