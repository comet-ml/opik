import MetricChartLegendContent from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartLegendContent";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ChartContainer, ChartLegend } from "@/components/ui/chart";
import { Spinner } from "@/components/ui/spinner";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Experiment } from "@/types/datasets";
import { uniq } from "lodash";
import React, { useMemo, useState } from "react";
import { Radar, RadarChart } from "recharts";

interface ExperimentsRadarChartProps {
  name: string;
  description: string;
  projectId: string;
  disableLoadingData: boolean;
  chartId: string;
  experiments: Experiment[];
  isPending: boolean;
}

const ExperimentsRadarChart: React.FC<ExperimentsRadarChartProps> = ({
  name,
  description,
  chartId,
  experiments,
  isPending,
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const experimentNames = useMemo(() => {
    return experiments.map((e) => e.name);
  }, [experiments]);

  const getRadarChartData = (experiments: Experiment[]) => {
    const scoreMap = experiments.reduce(
      (acc, experiment) => {
        const feedbackScores = experiment.feedback_scores || [];
        acc[experiment.id] = feedbackScores.reduce(
          (scores, feedback) => {
            scores[feedback.name] = feedback.value;
            return scores;
          },
          {} as Record<string, number>,
        );
        return acc;
      },
      {} as Record<string, Record<string, number>>,
    );

    const scoreNames = uniq(
      Object.values(scoreMap).flatMap(Object.keys),
    ).sort();

    return scoreNames.map((name) => {
      const dataPoint: Record<string, number | string> = { name };
      experiments.forEach((exp) => {
        dataPoint[exp.name] = scoreMap[exp.id]?.[name] || 0;
      });
      return dataPoint;
    });
  };

  const radarChartData = useMemo(
    () => getRadarChartData(experiments),
    [experiments],
  );

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(experimentNames);
  }, [experimentNames]);

  const renderContent = () => {
    if (isPending) {
      return (
        <div className="flex h-[var(--chart-height)] w-full  items-center justify-center">
          <Spinner />
        </div>
      );
    }

    return (
      <ChartContainer
        config={config}
        className="h-[var(--chart-height)] w-full"
      >
        <RadarChart
          data={radarChartData}
          margin={{
            top: 5,
            right: 10,
            left: 5,
            bottom: 5,
          }}
        >
          <ChartLegend
            content={
              <MetricChartLegendContent
                setActiveLine={setActiveLine}
                chartId={chartId}
              />
            }
          />

          <Radar
            data={data}
            stroke={config[line].color || ""}
            fill={config[line].color || ""}
            fillOpacity={0.3}
            strokeWidth={1.5}
          />
        </RadarChart>
      </ChartContainer>
    );
  };

  return (
    <Card>
      <CardHeader className="space-y-0.5 p-5">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          {description}
        </CardDescription>
      </CardHeader>
      <CardContent className="p-5">{renderContent()}</CardContent>
    </Card>
  );
};

export default ExperimentsRadarChart;
