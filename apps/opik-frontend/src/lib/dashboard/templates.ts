import {
  DashboardTemplate,
  WIDGET_TYPE,
  TEMPLATE_TYPE,
  TEMPLATE_SCOPE,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import {
  DASHBOARD_VERSION,
  createTemplateId,
  DEFAULT_MAX_EXPERIMENTS,
} from "@/lib/dashboard/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { LayoutDashboard, SquareActivity, FlaskConical } from "lucide-react";

const EXPERIMENT_COMPARISON_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.EXPERIMENT_COMPARISON),
  type: TEMPLATE_TYPE.EXPERIMENT_COMPARISON,
  scope: TEMPLATE_SCOPE.EXPERIMENTS,
  name: "Experiment insights",
  description:
    "Monitor experiment results and evaluation metrics over time to spot quality changes.",
  icon: FlaskConical,
  iconColor: "text-template-icon-experiments",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      {
        id: "template-section-1",
        title: "Experiment feedback scores",
        widgets: [
          {
            id: "template-widget-1",
            title: "Feedback scores",
            type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
            config: {
              dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
              chartType: CHART_TYPE.radar,
              experimentIds: [],
              filters: [],
              groups: [],
              feedbackScores: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-2",
            title: "Feedback scores distribution",
            type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
            config: {
              dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
              chartType: CHART_TYPE.bar,
              experimentIds: [],
              filters: [],
              groups: [],
              feedbackScores: [],
              overrideDefaults: false,
            },
          },
        ],
        layout: [
          {
            i: "template-widget-1",
            x: 0,
            y: 0,
            w: 2,
            h: 4,
          },
          {
            i: "template-widget-2",
            x: 2,
            y: 0,
            w: 4,
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
      experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      experimentFilters: [],
      maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
    },
  },
};

const PROJECT_METRICS_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.PROJECT_METRICS),
  type: TEMPLATE_TYPE.PROJECT_METRICS,
  scope: TEMPLATE_SCOPE.PROJECT,
  name: "Project metrics",
  description:
    "Track key metrics for your project, including token usage, cost, latency, and errors.",
  icon: LayoutDashboard,
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
              overrideDefaults: false,
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
              overrideDefaults: false,
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
              feedbackScores: [],
              overrideDefaults: false,
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
              overrideDefaults: false,
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
              overrideDefaults: false,
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
              feedbackScores: [],
              overrideDefaults: false,
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
              overrideDefaults: false,
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
              overrideDefaults: false,
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
              overrideDefaults: false,
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
      {
        id: "template-section-4",
        title: "Span metrics",
        widgets: [
          {
            id: "template-widget-10",
            title: "Span feedback scores",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              spanFilters: [],
              feedbackScores: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-11",
            title: "Number of spans",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.SPAN_COUNT,
              chartType: CHART_TYPE.line,
              spanFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-12",
            title: "Span duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.SPAN_DURATION,
              chartType: CHART_TYPE.line,
              spanFilters: [],
              overrideDefaults: false,
            },
          },
        ],
        layout: [
          {
            i: "template-widget-10",
            x: 0,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-11",
            x: 3,
            y: 0,
            w: 3,
            h: 4,
          },
          {
            i: "template-widget-12",
            x: 0,
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
      experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      experimentFilters: [],
      maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
    },
  },
};

const PERFORMANCE_OVERVIEW_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.PROJECT_PERFORMANCE),
  type: TEMPLATE_TYPE.PROJECT_PERFORMANCE,
  scope: TEMPLATE_SCOPE.PROJECT,
  name: "Performance overview",
  description:
    "Monitor latency, throughput, and error rates to keep your app running smoothly.",
  icon: SquareActivity,
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
              traceFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-2",
            title: "Threads",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "thread_count",
              traceFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-3",
            title: "Errors",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "error_count",
              traceFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-4",
            title: "Average Latency",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p50",
              traceFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-5",
            title: "Cost",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "total_estimated_cost_sum",
              traceFilters: [],
              overrideDefaults: false,
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
            w: 1,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
          {
            i: "template-widget-4",
            x: 3,
            y: 0,
            w: 1,
            h: 2,
            minW: 1,
            maxW: 6,
            minH: 2,
            maxH: 12,
          },
          {
            i: "template-widget-5",
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
            id: "template-widget-6",
            title: "Number of threads",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_COUNT,
              chartType: CHART_TYPE.bar,
              threadFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-7",
            title: "Number of traces",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_COUNT,
              chartType: CHART_TYPE.bar,
              traceFilters: [],
              overrideDefaults: false,
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
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
          {
            i: "template-widget-7",
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
            id: "template-widget-8",
            title: "Thread quality",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              threadFilters: [],
              feedbackScores: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-9",
            title: "Trace quality",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              traceFilters: [],
              feedbackScores: [],
              overrideDefaults: false,
            },
          },
        ],
        layout: [
          {
            i: "template-widget-8",
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
            i: "template-widget-9",
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
            id: "template-widget-10",
            title: "Trace duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_DURATION,
              chartType: CHART_TYPE.line,
              traceFilters: [],
              overrideDefaults: false,
            },
          },
          {
            id: "template-widget-11",
            title: "Thread duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_DURATION,
              chartType: CHART_TYPE.line,
              threadFilters: [],
              overrideDefaults: false,
            },
          },
        ],
        layout: [
          {
            i: "template-widget-10",
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
            i: "template-widget-11",
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
      experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      experimentFilters: [],
      maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
    },
  },
};

export const DASHBOARD_TEMPLATES: Record<TEMPLATE_TYPE, DashboardTemplate> = {
  [TEMPLATE_TYPE.PROJECT_METRICS]: PROJECT_METRICS_TEMPLATE,
  [TEMPLATE_TYPE.PROJECT_PERFORMANCE]: PERFORMANCE_OVERVIEW_TEMPLATE,
  [TEMPLATE_TYPE.EXPERIMENT_COMPARISON]: EXPERIMENT_COMPARISON_TEMPLATE,
};

export const TEMPLATE_LIST: DashboardTemplate[] = [
  PERFORMANCE_OVERVIEW_TEMPLATE,
  PROJECT_METRICS_TEMPLATE,
  EXPERIMENT_COMPARISON_TEMPLATE,
];

export const PROJECT_TEMPLATE_LIST: DashboardTemplate[] = [
  PERFORMANCE_OVERVIEW_TEMPLATE,
  PROJECT_METRICS_TEMPLATE,
];

export const EXPERIMENTS_TEMPLATE_LIST: DashboardTemplate[] = [
  EXPERIMENT_COMPARISON_TEMPLATE,
];
