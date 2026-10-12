import { ValueType } from "recharts/types/component/DefaultTooltipContent";

import {
  KpiEntityType,
  KpiMetricType,
} from "@/api/projects/useProjectKpiCards";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import {
  durationYTickFormatter,
  renderDurationTooltipValue,
  costYTickFormatter,
  renderCostTooltipValue,
} from "@/v2/pages-shared/dashboards/widgets/ProjectMetricsWidget/chartUtils";
import { ChartTooltipRenderValueArguments } from "@/shared/Charts/ChartTooltipContent/ChartTooltipContent";

export const TOTAL_COST_LABEL = "Total cost";

type ChartMetricConfig = {
  metricName: METRIC_NAME_TYPE;
  chartType: CHART_TYPE.line | CHART_TYPE.bar;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  colorMap?: Record<string, string>;
  filterLineCallback?: (lineName: string) => boolean;
  labelsMap?: Record<string, string>;
};

const CHART_VIOLET = "var(--chart-violet)";
const CHART_RED = "var(--chart-red)";
const CHART_BLUE = "var(--chart-blue)";
const CHART_TEAL = "var(--chart-teal)";

const COUNT_METRIC_MAP: Record<KpiEntityType, METRIC_NAME_TYPE> = {
  traces: METRIC_NAME_TYPE.TRACE_COUNT,
  spans: METRIC_NAME_TYPE.SPAN_COUNT,
  threads: METRIC_NAME_TYPE.THREAD_COUNT,
};

const ERROR_RATE_METRIC_MAP: Partial<Record<KpiEntityType, METRIC_NAME_TYPE>> =
  {
    traces: METRIC_NAME_TYPE.TRACE_ERROR_RATE,
    spans: METRIC_NAME_TYPE.SPAN_ERROR_RATE,
  };

const ERROR_RATE_LINE_NAME_MAP: Partial<Record<KpiEntityType, string>> = {
  traces: "trace_error_rate",
  spans: "span_error_rate",
};

const AVG_DURATION_LINE_NAME_MAP: Record<KpiEntityType, string> = {
  traces: "trace_average_duration",
  spans: "span_average_duration",
  threads: "thread_average_duration",
};

const AVG_DURATION_METRIC_MAP: Record<KpiEntityType, METRIC_NAME_TYPE> = {
  traces: METRIC_NAME_TYPE.TRACE_AVERAGE_DURATION,
  spans: METRIC_NAME_TYPE.SPAN_AVERAGE_DURATION,
  threads: METRIC_NAME_TYPE.THREAD_AVERAGE_DURATION,
};

const COST_METRIC_CONFIG: Record<
  KpiEntityType,
  { metricName: METRIC_NAME_TYPE; lineName: string }
> = {
  traces: { metricName: METRIC_NAME_TYPE.COST, lineName: "cost" },
  spans: { metricName: METRIC_NAME_TYPE.SPAN_COST, lineName: "span_cost" },
  threads: {
    metricName: METRIC_NAME_TYPE.THREAD_COST,
    lineName: "thread_cost",
  },
};

export const getChartConfig = (
  kpiType: KpiMetricType,
  entityType: KpiEntityType,
): ChartMetricConfig => {
  switch (kpiType) {
    case "count":
      return {
        metricName: COUNT_METRIC_MAP[entityType],
        chartType: CHART_TYPE.bar,
        colorMap: { [entityType]: CHART_VIOLET },
      };
    case "errors":
      return {
        metricName: ERROR_RATE_METRIC_MAP[entityType]!,
        chartType: CHART_TYPE.bar,
        colorMap: { [ERROR_RATE_LINE_NAME_MAP[entityType]!]: CHART_RED },
      };
    case "avg_duration":
      return {
        metricName: AVG_DURATION_METRIC_MAP[entityType],
        chartType: CHART_TYPE.bar,
        customYTickFormatter: durationYTickFormatter,
        renderValue: renderDurationTooltipValue,
        colorMap: { [AVG_DURATION_LINE_NAME_MAP[entityType]]: CHART_TEAL },
      };
    case "total_cost": {
      const { metricName, lineName } = COST_METRIC_CONFIG[entityType];
      return {
        metricName,
        chartType: CHART_TYPE.bar,
        customYTickFormatter: costYTickFormatter,
        renderValue: renderCostTooltipValue,
        colorMap: { [lineName]: CHART_BLUE },
        labelsMap: { [lineName]: TOTAL_COST_LABEL },
      };
    }
  }
};
