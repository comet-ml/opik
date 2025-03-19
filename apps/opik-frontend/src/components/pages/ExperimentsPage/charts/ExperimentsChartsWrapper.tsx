import React, { useMemo } from "react";
import uniq from "lodash/uniq";

import { cn } from "@/lib/utils";
import {
  DELETED_DATASET_ID,
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { formatDate } from "@/lib/date";
import ExperimentChartContainer, {
  ChartData,
} from "@/components/pages/ExperimentsPage/charts/ExperimentChartContainer";
import { Dataset } from "@/types/datasets";

export type ExperimentsChartsWrapperProps = {
  experiments: GroupedExperiment[];
  datasetsData: Dataset[];
};

const ExperimentsChartsWrapper: React.FC<ExperimentsChartsWrapperProps> = ({
  experiments,
  datasetsData,
}) => {
  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};

    experiments.forEach((experiment) => {
      if (experiment.virtual_dataset_id !== DELETED_DATASET_ID) {
        if (!groupsMap[experiment.virtual_dataset_id]) {
          groupsMap[experiment.virtual_dataset_id] = {
            dataset: experiment.dataset,
            data: [],
            lines: [],
          };
        }

        groupsMap[experiment.virtual_dataset_id].data.unshift({
          experimentId: experiment.id,
          experimentName: experiment.name,
          createdDate: formatDate(experiment.created_at),
          scores: (experiment.feedback_scores || []).reduce<
            Record<string, number>
          >((acc, score) => {
            acc[score.name] = score.value;
            return acc;
          }, {}),
        });

        groupsMap[experiment.virtual_dataset_id].lines = uniq([
          ...groupsMap[experiment.virtual_dataset_id].lines,
          ...(experiment.feedback_scores || []).map((s) => s.name),
        ]);
      }
    });

    return groupsMap;
  }, [experiments]);

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
        <ExperimentChartContainer
          key={dataset.id}
          chartId={dataset.id}
          chartData={chartsData[dataset.id]}
          dataset={dataset}
          className={chartClassName}
        />
      ))}
    </div>
  );
};

export default ExperimentsChartsWrapper;
