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
  isAverageScores = false,
}: FeedbackScoresChartsWrapperProps<TEntity>) => {
  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};
    let index = 0;

    entities.forEach((entity) => {
      if (entity.virtual_dataset_id !== DELETED_DATASET_ID) {
        if (!groupsMap[entity.virtual_dataset_id]) {
          groupsMap[entity.virtual_dataset_id] = {
            dataset: entity.dataset,
            data: [],
            lines: [],
            index,
          };
          index += 1;
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

    return Object.values(groupsMap).sort((g1, g2) => g1.index - g2.index);
  }, [entities]);

  const chartClassName =
    chartsData.length === 1
      ? "w-full"
      : chartsData.length === 2
        ? "basis-1/2"
        : "basis-[520px]";

  return (
    <div
      className={cn(
        "flex items-center gap-4 overflow-y-auto",
        chartsData.length > 0 && "mb-4",
      )}
    >
      {chartsData.map((data, index) => (
        <FeedbackScoresChartContainer
          key={data.dataset.id}
          chartId={data.dataset.id}
          chartData={chartsData[index]}
          dataset={data.dataset}
          className={chartClassName}
          isAverageScores={isAverageScores}
        />
      ))}
    </div>
  );
};

export default FeedbackScoresChartsWrapper;
