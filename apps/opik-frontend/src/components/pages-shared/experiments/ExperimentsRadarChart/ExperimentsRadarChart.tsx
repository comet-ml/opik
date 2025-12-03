import React, { useMemo } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { RadarDataPoint } from "@/types/chart";
import { ExperimentLabelsMap } from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails/useCompareExperimentsChartsData";
import RadarChart from "@/components/shared/Charts/RadarChart/RadarChart";

interface ExperimentsRadarChartProps {
  name: string;
  chartId: string;
  data: RadarDataPoint[];
  keys: string[];
  experimentLabelsMap: ExperimentLabelsMap;
}

const ExperimentsRadarChart: React.FunctionComponent<
  ExperimentsRadarChartProps
> = ({ name, chartId, data, keys, experimentLabelsMap }) => {
  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(keys, experimentLabelsMap);
  }, [keys, experimentLabelsMap]);

  return (
    <Card>
      <CardHeader className="space-y-0.5 px-5 py-4">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-3">
        <RadarChart
          chartId={chartId}
          config={config}
          data={data}
          angleAxisKey="name"
          showLegend
        />
      </CardContent>
    </Card>
  );
};

export default ExperimentsRadarChart;
