import React, { useMemo } from "react";
import uniq from "lodash/uniq";

import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import FeedbackScoresChartContainer from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContainer";
import { ChartData } from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";
import { Dataset } from "@/types/datasets";
import { DELETED_DATASET_ID } from "@/constants/grouping";
import { AggregatedFeedbackScore } from "@/types/shared";

type FeedbackScoresChartsWrapperProps<TEntity> = {
  entities: TEntity[];
  datasetsData: Dataset[];
  isAverageScores?: boolean;
};

const FeedbackScoresChartsWrapper = <
  TEntity extends {
    id: string;
    name: string;
    feedback_scores?: AggregatedFeedbackScore[];
    created_at: string;
    virtual_dataset_id: string;
    dataset: Dataset;
  },
>({
  entities,
  datasetsData,
  isAverageScores = false,
}: FeedbackScoresChartsWrapperProps<TEntity>) => {
  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};

    entities.forEach((entity) => {
      if (entity.virtual_dataset_id !== DELETED_DATASET_ID) {
        if (!groupsMap[entity.virtual_dataset_id]) {
          groupsMap[entity.virtual_dataset_id] = {
            dataset: entity.dataset,
            data: [],
            lines: [],
          };
        }

        groupsMap[entity.virtual_dataset_id].data.unshift({
          entityId: entity.id,
          entityName: entity.name,
          createdDate: formatDate(entity.created_at),
          scores: (entity.feedback_scores || []).reduce<Record<string, number>>(
            (acc, score) => {
              acc[score.name] = score.value;
              return acc;
            },
            {},
          ),
        });

        groupsMap[entity.virtual_dataset_id].lines = uniq([
          ...groupsMap[entity.virtual_dataset_id].lines,
          ...(entity.feedback_scores || []).map((s) => s.name),
        ]);
      }
    });

    return groupsMap;
  }, [entities]);

  const chartClassName =
    datasetsData.length === 1
      ? "w-full"
      : datasetsData.length === 2
        ? "basis-1/2"
        : "basis-[520px]";

  return (
    <div
      className={cn(
        "flex items-center gap-4 overflow-y-auto",
        datasetsData.length > 0 && "mb-4",
      )}
    >
      {datasetsData.map((dataset) => (
        <FeedbackScoresChartContainer
          key={dataset.id}
          chartId={dataset.id}
          chartData={chartsData[dataset.id]}
          dataset={dataset}
          className={chartClassName}
          isAverageScores={isAverageScores}
        />
      ))}
    </div>
  );
};

export default FeedbackScoresChartsWrapper;
