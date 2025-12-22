import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";

import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";

import { cn } from "@/lib/utils";
import {
  useDashboardStore,
  selectMixedConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import {
  UNSET_PROJECT_OPTION,
  UNSET_PROJECT_VALUE,
  WIDGET_PROJECT_SELECTOR_DESCRIPTION,
} from "@/lib/dashboard/utils";

import get from "lodash/get";

import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import {
  DashboardWidget,
  ProjectMetricsWidget,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  ProjectMetricsWidgetSchema,
  ProjectMetricsWidgetFormData,
} from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";
import { CHART_TYPE } from "@/constants/chart";

const METRIC_OPTIONS = [
  {
    value: METRIC_NAME_TYPE.FEEDBACK_SCORES,
    label: "Trace metrics",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_COUNT,
    label: "Number of traces",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_DURATION,
    label: "Trace duration",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TOKEN_USAGE,
    label: "Token usage",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.COST,
    label: "Estimated cost",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.FAILED_GUARDRAILS,
    label: "Failed guardrails",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_COUNT,
    label: "Number of threads",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_DURATION,
    label: "Thread duration",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
    label: "Thread metrics",
    filterType: "thread" as const,
  },
];

const CHART_TYPE_OPTIONS = [
  { value: CHART_TYPE.line, label: "Line chart" },
  { value: CHART_TYPE.bar, label: "Bar chart" },
];

const ProjectMetricsEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & ProjectMetricsWidget;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  const { config } = widgetData;
  const metricType = config.metricType || "";
  const chartType = config.chartType || CHART_TYPE.line;
  const localProjectId = config.projectId;

  const traceFilters = useMemo(
    () => config.traceFilters || [],
    [config.traceFilters],
  );
  const threadFilters = useMemo(
    () => config.threadFilters || [],
    [config.threadFilters],
  );
  const feedbackScores = useMemo(
    () => config.feedbackScores || [],
    [config.feedbackScores],
  );

  const globalProjectId = useDashboardStore((state) => {
    const config = selectMixedConfig(state);
    return config?.projectIds?.[0];
  });
  const projectId = localProjectId || globalProjectId || "";

  const selectedMetric = METRIC_OPTIONS.find((m) => m.value === metricType);
  const isTraceMetric = !metricType || selectedMetric?.filterType === "trace";
  const isThreadMetric = metricType && selectedMetric?.filterType === "thread";
  const isFeedbackScoreMetric =
    metricType === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES;

  const form = useForm<ProjectMetricsWidgetFormData>({
    resolver: zodResolver(ProjectMetricsWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      metricType,
      chartType,
      projectId: localProjectId,
      traceFilters,
      threadFilters,
      feedbackScores,
    },
  });

  const currentFilters = isTraceMetric
    ? form.watch("traceFilters") || []
    : form.watch("threadFilters") || [];

  useEffect(() => {
    if (isTraceMetric && form.formState.errors.traceFilters) {
      form.clearErrors("traceFilters");
    }
    if (isThreadMetric && form.formState.errors.threadFilters) {
      form.clearErrors("threadFilters");
    }
  }, [currentFilters.length, form, isTraceMetric, isThreadMetric]);

  useImperativeHandle(ref, () => ({
    submit: async () => {
      return await form.trigger();
    },
    isValid: form.formState.isValid,
  }));

  const handleMetricTypeChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        metricType: value,
      },
    });
  };

  const handleChartTypeChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        chartType: value as CHART_TYPE.line | CHART_TYPE.bar,
      },
    });
  };

  const handleProjectChange = (projectId: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        projectId,
      },
    });
  };

  const handleFeedbackScoresChange = (newFeedbackScores: string[]) => {
    updatePreviewWidget({
      config: {
        ...config,
        feedbackScores: newFeedbackScores,
      },
    });
  };

  return (
    <Form {...form}>
      <WidgetEditorBaseLayout>
        <div className="space-y-4">
          <FormField
            control={form.control}
            name="projectId"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["projectId"]);
              return (
                <FormItem>
                  <FormLabel>Project</FormLabel>
                  <FormControl>
                    <ProjectsSelectBox
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      value={field.value || UNSET_PROJECT_VALUE}
                      onValueChange={(value) => {
                        field.onChange(value);
                        handleProjectChange(value);
                      }}
                      customOptions={UNSET_PROJECT_OPTION}
                    />
                  </FormControl>
                  <Description>
                    {WIDGET_PROJECT_SELECTOR_DESCRIPTION}
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <FormField
            control={form.control}
            name="metricType"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["metricType"]);
              return (
                <FormItem>
                  <FormLabel>Metric type</FormLabel>
                  <FormControl>
                    <SelectBox
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      value={field.value}
                      onChange={(value) => {
                        field.onChange(value);
                        handleMetricTypeChange(value);
                      }}
                      options={METRIC_OPTIONS}
                      placeholder="Select a metric type"
                    />
                  </FormControl>
                  <Description>
                    Select the metric type you want this widget to display.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          {isFeedbackScoreMetric && projectId && (
            <FormField
              control={form.control}
              name="feedbackScores"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, [
                  "feedbackScores",
                ]);
                return (
                  <FormItem>
                    <FormLabel>Metrics</FormLabel>
                    <FormControl>
                      <FeedbackDefinitionsAndScoresSelectBox
                        value={field.value || []}
                        onChange={(value) => {
                          field.onChange(value);
                          handleFeedbackScoresChange(value);
                        }}
                        scoreSource={
                          metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES
                            ? ScoreSource.THREADS
                            : ScoreSource.TRACES
                        }
                        entityIds={[projectId]}
                        multiselect={true}
                        showSelectAll={true}
                        placeholder="All metrics"
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                      />
                    </FormControl>
                    <Description>
                      Select specific metrics to display. Leave empty to show
                      all available metrics.
                    </Description>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          )}

          <ProjectWidgetFiltersSection
            control={form.control}
            fieldName={isTraceMetric ? "traceFilters" : "threadFilters"}
            projectId={projectId}
            filterType={isTraceMetric ? "trace" : "thread"}
            onFiltersChange={(filters) => {
              updatePreviewWidget({
                config: {
                  ...config,
                  ...(isTraceMetric
                    ? { traceFilters: filters }
                    : { threadFilters: filters }),
                },
              });
            }}
          />

          <FormField
            control={form.control}
            name="chartType"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["chartType"]);
              return (
                <FormItem>
                  <FormLabel>Chart type</FormLabel>
                  <FormControl>
                    <SelectBox
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      value={field.value}
                      onChange={(value) => {
                        field.onChange(value);
                        handleChartTypeChange(value);
                      }}
                      options={CHART_TYPE_OPTIONS}
                      placeholder="Select chart type"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        </div>
      </WidgetEditorBaseLayout>
    </Form>
  );
});

ProjectMetricsEditor.displayName = "ProjectMetricsEditor";

export default ProjectMetricsEditor;
