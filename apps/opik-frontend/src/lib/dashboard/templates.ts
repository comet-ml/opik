import { DashboardTemplate, WIDGET_TYPE, TEMPLATE_ID } from "@/types/dashboard";
import { DASHBOARD_VERSION } from "@/lib/dashboard/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import { TRACE_DATA_TYPE } from "@/constants/traces";

const PROJECT_METRICS_TEMPLATE: DashboardTemplate = {
  id: TEMPLATE_ID.PROJECT_METRICS,
  title: "Project metrics",
  description:
    "Track token usage, cost, and performance metrics for traces and threads",
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

const COST_USAGE_TEMPLATE: DashboardTemplate = {
  id: TEMPLATE_ID.COST_USAGE,
  title: "Cost & Usage Monitoring",
  description:
    "Track LLM costs, token usage, and resource consumption over time",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      {
        id: "template-section-1",
        title: "Cost & Token Usage",
        widgets: [
          {
            id: "template-widget-1",
            title: "Token usage over time",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TOKEN_USAGE,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-2",
            title: "Estimated cost trends",
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
        title: "Summary Statistics",
        widgets: [
          {
            id: "template-widget-3",
            title: "Total estimated cost",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "total_estimated_cost_sum",
              traceFilters: [],
            },
          },
          {
            id: "template-widget-4",
            title: "Average tokens per trace",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "usage.total_tokens",
              traceFilters: [],
            },
          },
          {
            id: "template-widget-5",
            title: "Average cost per trace",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "total_estimated_cost",
              traceFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-3",
            x: 0,
            y: 0,
            w: 2,
            h: 2,
          },
          {
            i: "template-widget-4",
            x: 2,
            y: 0,
            w: 2,
            h: 2,
          },
          {
            i: "template-widget-5",
            x: 4,
            y: 0,
            w: 2,
            h: 2,
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

const PERFORMANCE_TEMPLATE: DashboardTemplate = {
  id: TEMPLATE_ID.PERFORMANCE,
  title: "Performance Overview",
  description:
    "Monitor application performance, latency, and throughput metrics",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      {
        id: "template-section-1",
        title: "Duration & Latency",
        widgets: [
          {
            id: "template-widget-1",
            title: "Trace duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_DURATION,
              chartType: CHART_TYPE.line,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-2",
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
        title: "Throughput",
        widgets: [
          {
            id: "template-widget-3",
            title: "Number of traces",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_COUNT,
              chartType: CHART_TYPE.bar,
              traceFilters: [],
            },
          },
          {
            id: "template-widget-4",
            title: "Number of threads",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_COUNT,
              chartType: CHART_TYPE.bar,
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
        ],
      },
      {
        id: "template-section-3",
        title: "Performance Statistics",
        widgets: [
          {
            id: "template-widget-5",
            title: "P50 Duration",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p50",
              traceFilters: [],
            },
          },
          {
            id: "template-widget-6",
            title: "P90 Duration",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p90",
              traceFilters: [],
            },
          },
          {
            id: "template-widget-7",
            title: "P99 Duration",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p99",
              traceFilters: [],
            },
          },
        ],
        layout: [
          {
            i: "template-widget-5",
            x: 0,
            y: 0,
            w: 2,
            h: 2,
          },
          {
            i: "template-widget-6",
            x: 2,
            y: 0,
            w: 2,
            h: 2,
          },
          {
            i: "template-widget-7",
            x: 4,
            y: 0,
            w: 2,
            h: 2,
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

export const DASHBOARD_TEMPLATES: Record<TEMPLATE_ID, DashboardTemplate> = {
  [TEMPLATE_ID.PROJECT_METRICS]: PROJECT_METRICS_TEMPLATE,
  [TEMPLATE_ID.COST_USAGE]: COST_USAGE_TEMPLATE,
  [TEMPLATE_ID.PERFORMANCE]: PERFORMANCE_TEMPLATE,
};

export const TEMPLATE_OPTIONS_ORDER: TEMPLATE_ID[] = [
  TEMPLATE_ID.PROJECT_METRICS,
  TEMPLATE_ID.COST_USAGE,
  TEMPLATE_ID.PERFORMANCE,
];
