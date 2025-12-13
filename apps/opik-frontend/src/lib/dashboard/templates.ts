import {
  DashboardTemplate,
  WIDGET_TYPE,
  TEMPLATE_TYPE,
} from "@/types/dashboard";
import { DASHBOARD_VERSION, createTemplateId } from "@/lib/dashboard/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { ChartLine, Activity } from "lucide-react";

const PROJECT_METRICS_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.PROJECT_METRICS),
  type: TEMPLATE_TYPE.PROJECT_METRICS,
  title: "Project metrics",
  description:
    "Track token usage, cost, and performance metrics for traces and threads",
  icon: ChartLine,
  iconColor: "text-template-icon-metrics",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      {
        id: "template-section-1",
        title: "Project metrics",
        widgets: [
          {
            id: "template-widget-1",
            title: "Token usage",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TOKEN_USAGE,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-2",
            title: "Estimated cost",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.COST,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-1",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-2",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
          },
        ],
      },
      {
        id: "template-section-2",
        title: "Thread metrics",
        widgets: [
          {
            id: "template-widget-3",
            title: "Threads feedback scores",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              threadFilters: [],
            },
          },
          {
            id: "template-widget-4",
            title: "Number of threads",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_COUNT,
              chartType: CHART_TYPE.line,
              threadFilters: [],
            },
          },
          {
            id: "template-widget-5",
            title: "Thread duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_DURATION,
              chartType: CHART_TYPE.line,
              threadFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-3",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-4",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-5",
            x: 0,
            y: 4,
            w: 3,
            h: 4,
          },
        ],
      },
      {
        id: "template-section-3",
        title: "Trace metrics",
        widgets: [
          {
            id: "template-widget-6",
            title: "Trace feedback scores",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-7",
            title: "Number of traces",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_COUNT,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-8",
            title: "Trace duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_DURATION,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-9",
            title: "Failed guardrails",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.FAILED_GUARDRAILS,
              chartType: CHART_TYPE.bar,
              traceFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-6",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-7",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-8",
            x: 0,
            y: 4,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-9",
            x: 3,
            y: 4,
            w: 3,
            h: 4,
          },
        ],
      },
    ],
    lastModified: Date.now(),
    config: {
      dateRange: DEFAULT_DATE_PRESET,
      projectIds: [],
      experimentIds: [],
    },
  },
};

const PERFORMANCE_OVERVIEW_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.PROJECT_PERFORMANCE),
  type: TEMPLATE_TYPE.PROJECT_PERFORMANCE,
  title: "Performance Overview",
  description:
    "Comprehensive performance monitoring including traces, threads, quality metrics, and latency",
  icon: Activity,
  iconColor: "text-template-icon-performance",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      {
        id: "template-section-1",
        title: "Project overview",
        widgets: [
          {
            id: "template-widget-1",
            title: "Traces",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "trace_count",
            },
          },
          {
            id: "template-widget-2",
            title: "Errors",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "error_count",
            },
          },
          {
            id: "template-widget-3",
            title: "Average Latency",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p50",
              traceFilters: [],
            },
          },
          {
            id: "template-widget-4",
            title: "Cost",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "total_estimated_cost_sum",
            },
          },
        ],
        layout: [
          {
            i: "template-widget-1",
            x: 0,
            y: 0,
            w: 1,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
          {
            i: "template-widget-2",
            x: 1,
            y: 0,
            w: 1,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
          {
            i: "template-widget-3",
            x: 2,
            y: 0,
            w: 2,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
          {
            i: "template-widget-4",
            x: 4,
            y: 0,
            w: 2,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
        ],
      },
      {
        id: "template-section-2",
        title: "Threads and traces volume",
        widgets: [
          {
            id: "template-widget-5",
            title: "Number of threads",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_COUNT,
              chartType: CHART_TYPE.bar,
              threadFilters: [],
            },
          },
          {
            id: "template-widget-6",
            title: "Number of traces",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_COUNT,
              chartType: CHART_TYPE.bar,
              traceFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-5",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
          {
            i: "template-widget-6",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
        ],
      },
      {
        id: "template-section-3",
        title: "Quality overview",
        widgets: [
          {
            id: "template-widget-7",
            title: "Thread quality",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
            },
          },
          {
            id: "template-widget-8",
            title: "Trace quality",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
            },
          },
        ],
        layout: [
          {
            i: "template-widget-7",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
          {
            i: "template-widget-8",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
        ],
      },
      {
        id: "template-section-4",
        title: "Duration & Latency",
        widgets: [
          {
            id: "template-widget-9",
            title: "Trace duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_DURATION,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-10",
            title: "Thread duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_DURATION,
              chartType: CHART_TYPE.line,
              threadFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-9",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
          {
            i: "template-widget-10",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
        ],
      },
    ],
    lastModified: Date.now(),
    config: {
      dateRange: DEFAULT_DATE_PRESET,
      projectIds: [],
      experimentIds: [],
    },
  },
};

export const DASHBOARD_TEMPLATES: Record<TEMPLATE_TYPE, DashboardTemplate> = {
  [TEMPLATE_TYPE.PROJECT_METRICS]: PROJECT_METRICS_TEMPLATE,
  [TEMPLATE_TYPE.PROJECT_PERFORMANCE]: PERFORMANCE_OVERVIEW_TEMPLATE,
};

export const TEMPLATE_LIST: DashboardTemplate[] = [
  PROJECT_METRICS_TEMPLATE,
  PERFORMANCE_OVERVIEW_TEMPLATE,
];
