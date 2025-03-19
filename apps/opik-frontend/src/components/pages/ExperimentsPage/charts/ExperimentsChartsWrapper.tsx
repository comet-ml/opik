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
import { Spinner } from "@/components/ui/spinner";

export type ExperimentsChartsWrapperProps = {
  experiments: GroupedExperiment[];
  datasetsIds: string[];
};

const ExperimentsChartsWrapper: React.FC<ExperimentsChartsWrapperProps> = ({
  experiments,
  datasetsIds,
}) => {
  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};
    let index = 0;

    experiments.forEach((experiment) => {
      if (experiment.virtual_dataset_id !== DELETED_DATASET_ID) {
        if (!groupsMap[experiment.virtual_dataset_id]) {
          groupsMap[experiment.virtual_dataset_id] = {
            dataset: experiment.dataset,
            data: [],
            lines: [],
            index,
          };
          index += 1;
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
    datasetsIds.length === 1
      ? "w-full"
      : datasetsIds.length === 2
        ? "basis-1/2"
        : "basis-[520px]";

  return (
    <div
      className={cn(
        "flex items-center gap-4 overflow-y-auto",
        datasetsIds.length > 0 && "mb-4",
      )}
    >
      {datasetsIds.map((datasetId) => {
        const chartData = chartsData[datasetId];

        if (!chartData) {
          return (
            <div
              key={datasetId}
              className={cn(
                "flex h-52 w-full items-center justify-center rounded-lg border bg-card shadow-sm",
                chartClassName,
              )}
            >
              <Spinner />
            </div>
          );
        }
        return (
          <ExperimentChartContainer
            key={datasetId}
            chartData={chartData}
            className={chartClassName}
          />
        );
      })}
    </div>
  );
};

export default ExperimentsChartsWrapper;
