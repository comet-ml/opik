import {
  DashboardTemplate,
  WIDGET_TYPE,
  TEMPLATE_TYPE,
  TEMPLATE_SCOPE,
} from "@/types/dashboard";
import { DASHBOARD_VERSION, createTemplateId } from "@/lib/dashboard/utils";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { SquareActivity } from "lucide-react";

import { BREAKDOWN_FIELD } from "@/types/dashboard";

export const EXPERIMENT_COMPARISON_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.EXPERIMENT_COMPARISON),
  type: TEMPLATE_TYPE.EXPERIMENT_COMPARISON,
  scope: TEMPLATE_SCOPE.EXPERIMENTS,
  name: "Experiments overview",
  description:
    "Monitor experiment results and evaluation metrics over time to spot quality changes.",
  icon: SquareActivity,
  iconColor: "text-chart-pink",
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
              chartType: CHART_TYPE.radar,
              filters: [],
              groups: [],
              feedbackScores: [],
            },
          },
          {
            id: "template-widget-2",
            title: "Feedback scores distribution",
            type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
            config: {
              chartType: CHART_TYPE.bar,
              filters: [],
              groups: [],
              feedbackScores: [],
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
  },
};

const PROJECT_OVERVIEW_TEMPLATE: DashboardTemplate = {
  id: createTemplateId(TEMPLATE_TYPE.PROJECT_OVERVIEW),
  type: TEMPLATE_TYPE.PROJECT_OVERVIEW,
  scope: TEMPLATE_SCOPE.PROJECT,
  name: "Project overview",
  description:
    "At-a-glance health check: traces, errors, latency, cost, and quality trends.",
  icon: SquareActivity,
  iconColor: "text-chart-pink",
  config: {
    version: DASHBOARD_VERSION,
    sections: [
      // Section 1: "At a glance" — 5 stats cards
      {
        id: "template-section-1",
        title: "At a glance",
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
              traceFilters: [],
            },
          },
          {
            id: "template-widget-3",
            title: "Latency (p50)",
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
            title: "Latency (p99)",
            type: WIDGET_TYPE.PROJECT_STATS_CARD,
            config: {
              source: TRACE_DATA_TYPE.traces,
              projectId: "",
              metric: "duration.p99",
              traceFilters: [],
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
      // Section 2: "Volume, latency & cost" — 3 charts
      {
        id: "template-section-2",
        title: "Volume, latency & cost",
        widgets: [
          {
            id: "template-widget-6",
            title: "Trace volume",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_COUNT,
              chartType: CHART_TYPE.bar,
              traceFilters: [],
              breakdown: { field: BREAKDOWN_FIELD.NAME },
            },
          },
          {
            id: "template-widget-7",
            title: "Trace duration",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.TRACE_DURATION,
              chartType: CHART_TYPE.line,
              traceFilters: [],
              breakdown: { field: BREAKDOWN_FIELD.NAME },
            },
          },
          {
            id: "template-widget-8",
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
          {
            i: "template-widget-8",
            x: 0,
            y: 4,
            w: 6,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
        ],
      },
      // Section 3: "Quality & conversations" — 4 charts
      {
        id: "template-section-3",
        title: "Quality & conversations",
        widgets: [
          {
            id: "template-widget-9",
            title: "Trace feedback scores",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              traceFilters: [],
              feedbackScores: [],
            },
          },
          {
            id: "template-widget-10",
            title: "Thread volume",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_COUNT,
              chartType: CHART_TYPE.bar,
              threadFilters: [],
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
            },
          },
          {
            id: "template-widget-12",
            title: "Thread feedback scores",
            type: WIDGET_TYPE.PROJECT_METRICS,
            config: {
              metricType: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
              chartType: CHART_TYPE.line,
              threadFilters: [],
              feedbackScores: [],
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
          {
            i: "template-widget-11",
            x: 0,
            y: 4,
            w: 3,
            h: 4,
            minW: 2,
            maxW: 6,
            minH: 4,
            maxH: 12,
          },
          {
            i: "template-widget-12",
            x: 3,
            y: 4,
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
  },
};

export const TEMPLATE_LIST: DashboardTemplate[] = [
  PROJECT_OVERVIEW_TEMPLATE,
  EXPERIMENT_COMPARISON_TEMPLATE,
];

export const PROJECT_TEMPLATE_LIST: DashboardTemplate[] = [
  PROJECT_OVERVIEW_TEMPLATE,
];

// Old project templates replaced by PROJECT_OVERVIEW — users may still have these in localStorage
export const DEPRECATED_PROJECT_METRICS_ID = "template:project-metrics";
export const DEPRECATED_PROJECT_PERFORMANCE_ID = "template:project-performance";
