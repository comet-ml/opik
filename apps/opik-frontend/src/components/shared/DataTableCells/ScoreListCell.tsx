import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import FeedbackScoreHoverCard from "../FeedbackScoreTag/FeedbackScoreHoverCard";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import ChildrenWidthMeasurer from "../ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import {
  ScoreType,
  SCORE_TYPE_EXPERIMENT,
  SCORE_TYPE_FEEDBACK,
} from "@/types/shared";

const TAG_GAP = 6;
const COUNTER_WIDTH = 30;

type CustomMeta<TData> = {
  getHoverCardName: (row: TData) => string;
  scoreType?: ScoreType;
};

type RowWithScores = {
  feedback_scores?: TraceFeedbackScore[];
  experiment_scores?: TraceFeedbackScore[];
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

type ScoreListCellProps<TData> = {
  context: CellContext<TData, unknown>;
  scoreType: ScoreType;
};

const ScoreListCell = <TData,>({
  context,
  scoreType,
}: ScoreListCellProps<TData>) => {
  // Get scores from the appropriate field based on scoreType
  const rowData = context.row.original as RowWithScores;
  const scoreList =
    (scoreType === SCORE_TYPE_EXPERIMENT
      ? rowData.experiment_scores
      : rowData.feedback_scores) ?? [];

  const { getHoverCardName } = (context.column.columnDef.meta?.custom ??
    {}) as CustomMeta<TData>;

  const hoverCardName = getHoverCardName(context.row.original);
  const isEmpty = !scoreList.length;
  const [visibleCount, setVisibleCount] = useState(0);
  const widthList = useRef<number[]>([]);
  const remainingCount = scoreList.length - visibleCount;

  const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>((node) => {
    const visibleCount = calculateVisibleCount(
      node.clientWidth,
      widthList.current,
      sortedList.length,
    );
    setVisibleCount(visibleCount);
  });

  const sortedList = scoreList.sort((c1, c2) => c1.name.localeCompare(c2.name));

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
          <FeedbackScoreHoverCard
            title={hoverCardName}
            scoreType={scoreType}
            scores={sortedList}
            hidden={!remainingCount}
          >
            <div className="flex min-w-0 flex-1">
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
            </div>
          </FeedbackScoreHoverCard>
        </div>
      )}
    </CellWrapper>
  );
};

type AggregationCustomMeta = {
  aggregationKey?: string;
  dataFormatter?: (value: number) => string;
};

type ScoreListAggregationCellProps<TData> = {
  context: CellContext<TData, string>;
  scoreType: ScoreType;
};

const ScoreListAggregationCell = <TData,>({
  context,
  scoreType,
}: ScoreListAggregationCellProps<TData>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatNumericData } = (custom ??
    {}) as AggregationCustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};

  // Get scores from the appropriate field based on scoreType
  const scoresKey =
    scoreType === SCORE_TYPE_EXPERIMENT
      ? "experiment_scores"
      : aggregationKey ?? "feedback_scores";
  const scoresRaw = get(data, scoresKey, undefined);

  const addAvgSuffix = scoreType === SCORE_TYPE_FEEDBACK;

  let value = "";

  const formatScores = (scores: unknown) => {
    if (!isArray(scores)) return [];
    return (scores as TraceFeedbackScore[]).map((item: TraceFeedbackScore) => {
      const name = addAvgSuffix ? `${item.name} (avg)` : item.name;
      return `${name}: ${
        isNumber(item.value) ? dataFormatter(item.value) : "-"
      }`;
    });
  };

  const scoresFormatted = formatScores(scoresRaw);
  value = scoresFormatted.join(", ");

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

ScoreListCell.Aggregation = ScoreListAggregationCell;

export default ScoreListCell;
