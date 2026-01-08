import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import FeedbackScoreHoverCard from "../FeedbackScoreTag/FeedbackScoreHoverCard";
import ChildrenWidthMeasurer from "../ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import {
  ScoreType,
  SCORE_TYPE_EXPERIMENT,
  SCORE_TYPE_FEEDBACK,
} from "@/types/shared";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

const FEEDBACK_SCORE_LIST_CONFIG = { itemGap: 6 };

type CustomMeta<TData> = {
  getHoverCardName: (row: TData) => string;
  areAggregatedScores?: boolean;
};

type RowWithScores = {
  feedback_scores?: TraceFeedbackScore[];
  experiment_scores?: TraceFeedbackScore[];
};

type ScoreWithType = TraceFeedbackScore & {
  scoreType: ScoreType;
};

const FeedbackScoreListCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  // Combine all scores together, marking each with its type and adding (avg) suffix
  const rowData = context.row.original as RowWithScores;
  const scoreList: ScoreWithType[] = [
    ...(rowData.feedback_scores ?? []).map((score) => ({
      ...score,
      name: `${score.name} (avg)`,
      scoreType: SCORE_TYPE_FEEDBACK,
    })),
    ...(rowData.experiment_scores ?? []).map((score) => ({
      ...score,
      scoreType: SCORE_TYPE_EXPERIMENT,
    })),
  ];

  const { getHoverCardName, areAggregatedScores } = (context.column.columnDef
    .meta?.custom ?? {}) as CustomMeta<TData>;

  const hoverCardName = getHoverCardName(context.row.original);
  const isEmpty = !scoreList.length;

  const sortedList = scoreList.sort((c1, c2) => c1.name.localeCompare(c2.name));

  const { cellRef, visibleItems, hasHiddenItems, remainingCount, onMeasure } =
    useVisibleItemsByWidth(sortedList, FEEDBACK_SCORE_LIST_CONFIG);

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
            areAggregatedScores={areAggregatedScores}
            scores={sortedList}
            hidden={!hasHiddenItems}
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
                {visibleItems.map<React.ReactNode>((item) => (
                  <FeedbackScoreTag
                    key={item.name}
                    label={item.name}
                    value={item.value}
                    className="min-w-0"
                  />
                ))}
                {hasHiddenItems && (
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

const FeedbackScoreListAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatNumericData } = (custom ??
    {}) as AggregationCustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};

  // Combine all scores together, marking each with its type and adding (avg) suffix
  const feedbackScores = get(data, aggregationKey ?? "feedback_scores", []);
  const experimentScores = get(data, "experiment_scores", []);

  const feedbackScoresWithType: ScoreWithType[] = (
    isArray(feedbackScores) ? feedbackScores : []
  ).map((score: TraceFeedbackScore) => ({
    ...score,
    name: `${score.name} (avg)`,
    scoreType: SCORE_TYPE_FEEDBACK,
  }));

  const experimentScoresWithType: ScoreWithType[] = (
    isArray(experimentScores) ? experimentScores : []
  ).map((score: TraceFeedbackScore) => ({
    ...score,
    scoreType: SCORE_TYPE_EXPERIMENT,
  }));

  const scoresRaw: ScoreWithType[] = [
    ...feedbackScoresWithType,
    ...experimentScoresWithType,
  ];

  const formatScores = (scores: ScoreWithType[]) => {
    return scores.map(
      (item) =>
        `${item.name}: ${
          isNumber(item.value) ? dataFormatter(item.value) : "-"
        }`,
    );
  };

  const scoresFormatted = formatScores(scoresRaw);
  const value = scoresFormatted.join(", ");

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

FeedbackScoreListCell.Aggregation = FeedbackScoreListAggregationCell;

export default FeedbackScoreListCell;
