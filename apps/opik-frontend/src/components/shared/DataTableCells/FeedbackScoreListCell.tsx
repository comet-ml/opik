import React, { useCallback, useEffect, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import FeedbackScoreTagMeasurer from "../FeedbackScoreTag/FeedbackScoreTagMeasurer";
import FeedbackScoreHoverCard from "../FeedbackScoreTag/FeedbackScoreHoverCard";

const TAG_GAP = 6;

const FeedbackScoreListCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScoreList = context.getValue() as TraceFeedbackScore[];
  const isEmpty = !feedbackScoreList?.length;
  const [visibleCount, setVisibleCount] = useState(0);
  const widthList = useRef<number[]>([]);
  const remainingTagRef = useRef<HTMLDivElement>(null);
  const remainingCount = feedbackScoreList.length - visibleCount;

  const isResizing = context.column.getIsResizing();

  const sortedList = feedbackScoreList.sort((c1, c2) =>
    c1.name.localeCompare(c2.name),
  );

  const calcVisibleCount = useCallback(() => {
    let totalWidth = context.column.getSize();
    const lastIdx = widthList.current.length - 1;

    const remainingTagWidth =
      remainingTagRef.current?.getBoundingClientRect().width || 0;

    totalWidth = totalWidth - remainingTagWidth;

    for (let idx = 0; idx < widthList.current.length; idx++) {
      const tagWidth = widthList.current[idx];

      totalWidth = totalWidth - tagWidth;

      if (idx !== 0 && idx !== lastIdx) {
        totalWidth = totalWidth - TAG_GAP * 2;
      }

      if (totalWidth < 0 || idx === lastIdx) {
        setVisibleCount(idx);
        break;
      }
    }
  }, []);

  const onMeasure = useCallback((measureList: number[]) => {
    widthList.current = measureList;
    calcVisibleCount();
  }, []);

  useEffect(() => {
    calcVisibleCount();
  }, [isResizing, calcVisibleCount]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="p-0 py-1"
    >
      {isEmpty ? (
        "-"
      ) : (
        <FeedbackScoreHoverCard tagList={sortedList} hidden={!remainingCount}>
          <div className="flex size-full items-center justify-start gap-1.5 overflow-hidden p-0 py-1">
            <FeedbackScoreTagMeasurer
              onMeasure={onMeasure}
              tagList={sortedList}
            />
            {sortedList.slice(0, visibleCount).map<React.ReactNode>((item) => (
              <FeedbackScoreTag
                key={item.name}
                label={item.name}
                value={item.value}
                reason={item.reason}
              />
            ))}
            {Boolean(remainingCount) && (
              <div
                ref={remainingTagRef}
                className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate"
              >
                +{remainingCount}
              </div>
            )}
          </div>
        </FeedbackScoreHoverCard>
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreListCell;
