import React, { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, YAxis } from "recharts";
import uniq from "lodash/uniq";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import {
  DELETED_DATASET_ID,
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { Dataset } from "@/types/datasets";
import { generateTagVariant } from "@/lib/traces";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import ExperimentChartTooltipContent from "@/components/pages/ExperimentsPage/ExperimentChartTooltipContent";
import ExperimentChartLegendContent from "@/components/pages/ExperimentsPage/ExperimentChartLegendContent";

export type ExperimentsChartsWrapperProps = {
  experiments: GroupedExperiment[];
};

type DataRecord = {
  experimentId: string;
  experimentName: string;
  createdDate: string;
  scores: Record<string, number>;
};

type GroupData = {
  dataset: Dataset;
  data: DataRecord[];
  lines: string[];
  index: number;
};

// TODO lala overflow on tooltip

const ExperimentsChartsWrapper: React.FC<ExperimentsChartsWrapperProps> = ({
  experiments,
}) => {
  const [hiddenLines, setHiddenLines] = useState<Record<string, string[]>>({});

  const chartData = useMemo(() => {
    const groupsMap: Record<string, GroupData> = {};
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
    chartData.length === 1 || chartData.length === 2
      ? "basis-1/2"
      : "basis-2/5 min-w-[40%]";

  const renderChart = (groupData: GroupData) => {
    const config = groupData.lines.reduce<ChartConfig>((acc, line) => {
      acc[line] = {
        label: line,
        color: TAG_VARIANTS_COLOR_MAP[generateTagVariant(line)!],
      };
      return acc;
    }, {});

    const {
      dataset: { id: chartId, name },
    } = groupData;

    return (
      <Card className={cn("min-w-[400px]", chartClassName)} key={chartId}>
        <CardHeader>
          <CardTitle>{name}</CardTitle>
        </CardHeader>
        <CardContent>
          <ChartContainer config={config} className="h-32 w-full">
            <LineChart
              data={groupData.data}
              margin={{ top: 10, bottom: 10, left: 0, right: 0 }}
            >
              <CartesianGrid vertical={false} />
              <YAxis
                includeHidden
                axisLine={false}
                tickLine={false}
                tick={{
                  stroke: "#94A3B8",
                  fontSize: 10,
                  fontWeight: 200,
                }}
                tickMargin={10}
              />
              <ChartTooltip
                cursor={false}
                content={<ExperimentChartTooltipContent />}
              />
              <ChartLegend
                verticalAlign="top"
                layout="vertical"
                align="right"
                content={
                  <ExperimentChartLegendContent
                    setHideState={setHiddenLines}
                    chartId={chartId}
                  />
                }
                width={120}
                height={128}
              />
              {groupData.lines.map((line) => {
                const hide = (hiddenLines[chartId] || []).includes(line);

                return (
                  <Line
                    type="natural"
                    key={line}
                    dataKey={(x) => x.scores[line] || undefined}
                    name={config[line].label as string}
                    stroke={config[line].color as string}
                    dot={{ strokeWidth: 3, r: 1 }}
                    hide={hide}
                  />
                );
              })}
            </LineChart>
          </ChartContainer>
        </CardContent>
      </Card>
    );
  };

  return (
    <div className="mb-6 flex items-center gap-6 overflow-y-auto">
      {chartData.map((data) => renderChart(data))}
    </div>
  );
};

export default ExperimentsChartsWrapper;
