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

export type ExperimentsChartsWrapperProps = {
  experiments: GroupedExperiment[];
};

const ExperimentsChartsWrapper: React.FC<ExperimentsChartsWrapperProps> = ({
  experiments,
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

    return Object.values(groupsMap).sort((g1, g2) => g1.index - g2.index);
  }, [experiments]);

  const chartClassName =
    chartsData.length === 1
      ? "w-full"
      : chartsData.length === 2
        ? "basis-1/2"
        : "w-full max-w-[520px]";

  return (
    <div
      className={cn(
        "flex items-center gap-4 overflow-y-auto",
        chartsData.length > 0 && "mb-4",
      )}
    >
      {chartsData.map((data) => (
        <ExperimentChartContainer
          key={data.dataset.id}
          chartData={data}
          className={chartClassName}
        />
      ))}
    </div>
  );
};

export default ExperimentsChartsWrapper;
