import React from "react";

import {
  METRIC_NAME_TYPE,
  INTERVAL_TYPE,
} from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { formatCost } from "@/lib/money";
import MetricContainerChart from "./MetricChart/MetricChartContainer";
import { INTERVAL_DESCRIPTIONS } from "./utils";

const renderCostTooltipValue = ({ value }: ChartTooltipRenderValueArguments) =>
  formatCost(value as number);

interface ProjectMetricsSectionProps {
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string;
  intervalEnd: string;
  hasTraces: boolean;
}

const ProjectMetricsSection: React.FC<ProjectMetricsSectionProps> = ({
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  hasTraces,
}) => {
  if (!hasTraces) {
    return null;
  }

  return (
    <div className="pt-6">
      <div className="sticky top-0 z-10 bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-s truncate break-words">Project metrics</h2>
      </div>
      <div
        className="grid grid-cols-1 gap-4 py-4 md:grid-cols-2"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        <div>
          <MetricContainerChart
            chartId="token_usage_chart"
            key="token_usage_chart"
            name="Token usage"
            description={INTERVAL_DESCRIPTIONS.TOTALS[interval]}
            metricName={METRIC_NAME_TYPE.TOKEN_USAGE}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            chartType="line"
          />
        </div>
        <div>
          <MetricContainerChart
            chartId="estimated_cost_chart"
            key="estimated_cost_chart"
            name="Estimated cost"
            description={INTERVAL_DESCRIPTIONS.COST[interval]}
            metricName={METRIC_NAME_TYPE.COST}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            renderValue={renderCostTooltipValue}
            chartType="line"
          />
        </div>
      </div>
    </div>
  );
};

export default ProjectMetricsSection;
