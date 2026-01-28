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
import { LoadableSelectBox } from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import WidgetOverrideDefaultsSection from "@/components/shared/Dashboard/widgets/shared/WidgetOverrideDefaultsSection/WidgetOverrideDefaultsSection";
import ProjectMetricsBreakdownSection from "./ProjectMetricsBreakdownSection";

import { cn } from "@/lib/utils";
import {
  useDashboardStore,
  selectMixedConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";

import get from "lodash/get";

import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import useProjectTokenUsageNames from "@/api/projects/useProjectTokenUsageNames";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import {
  DashboardWidget,
  ProjectMetricsWidget,
  WidgetEditorHandle,
  BreakdownConfig,
} from "@/types/dashboard";
import {
  ProjectMetricsWidgetSchema,
  ProjectMetricsWidgetFormData,
} from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";
import { CHART_TYPE } from "@/constants/chart";
import { Filter } from "@/types/filters";
import { BREAKDOWN_FIELD } from "./breakdown";

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
  {
    value: METRIC_NAME_TYPE.SPAN_COUNT,
    label: "Number of spans",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_DURATION,
    label: "Span duration",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES,
    label: "Span metrics",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
    label: "Span token usage",
    filterType: "span" as const,
  },
];

const CHART_TYPE_OPTIONS = [
  { value: CHART_TYPE.line, label: "Line chart" },
  { value: CHART_TYPE.bar, label: "Bar chart" },
];

const DURATION_METRIC_OPTIONS = [
  { value: "p50", label: "P50 (Median)" },
  { value: "p90", label: "P90" },
  { value: "p99", label: "P99" },
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
  const overrideDefaults = config.overrideDefaults || false;

  const traceFilters = useMemo<Filter[]>(
    () => (config.traceFilters as Filter[] | undefined) || [],
    [config.traceFilters],
  );
  const threadFilters = useMemo<Filter[]>(
    () => (config.threadFilters as Filter[] | undefined) || [],
    [config.threadFilters],
  );
  const spanFilters = useMemo<Filter[]>(
    () => (config.spanFilters as Filter[] | undefined) || [],
    [config.spanFilters],
  );
  const feedbackScores = useMemo<string[]>(
    () => (config.feedbackScores as string[] | undefined) || [],
    [config.feedbackScores],
  );
  const durationMetrics = useMemo<string[]>(
    () => (config.durationMetrics as string[] | undefined) || [],
    [config.durationMetrics],
  );
  const usageMetrics = useMemo<string[]>(
    () => (config.usageMetrics as string[] | undefined) || [],
    [config.usageMetrics],
  );

  const breakdown = useMemo(
    () =>
      config.breakdown || {
        field: BREAKDOWN_FIELD.NONE,
      },
    [config.breakdown],
  );

  const globalConfig = useDashboardStore((state) => {
    const config = selectMixedConfig(state);
    return {
      projectId: config?.projectIds?.[0],
      dateRange: config?.dateRange ?? DEFAULT_DATE_PRESET,
    };
  });
  const projectId = localProjectId || globalConfig.projectId || "";

  const selectedMetric = METRIC_OPTIONS.find((m) => m.value === metricType);
  const isTraceMetric = !metricType || selectedMetric?.filterType === "trace";
  const isThreadMetric = metricType && selectedMetric?.filterType === "thread";
  const isSpanMetric = metricType && selectedMetric?.filterType === "span";
  const isFeedbackScoreMetric =
    metricType === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES;
  const isDurationMetric =
    metricType === METRIC_NAME_TYPE.TRACE_DURATION ||
    metricType === METRIC_NAME_TYPE.THREAD_DURATION ||
    metricType === METRIC_NAME_TYPE.SPAN_DURATION;
  const isTokenUsageMetric =
    metricType === METRIC_NAME_TYPE.TOKEN_USAGE ||
    metricType === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

  const { data: tokenUsageNamesData, isPending: isLoadingUsageKeys } =
    useProjectTokenUsageNames(
      {
        projectId,
      },
      {
        enabled: isTokenUsageMetric && !!projectId,
      },
    );

  // Map token usage names to select options
  const usageKeyOptions = useMemo(() => {
    if (!tokenUsageNamesData?.names) return [];

    return tokenUsageNamesData.names
      .sort((a, b) => a.localeCompare(b))
      .map((name) => ({
        value: name,
        label: name,
      }));
  }, [tokenUsageNamesData?.names]);

  // For feedback score metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneFeedbackScoreSelected = feedbackScores.length === 1;
  const isGroupByDisabledForFeedbackScore =
    isFeedbackScoreMetric && !hasExactlyOneFeedbackScoreSelected;

  // For duration metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneDurationMetricSelected = durationMetrics.length === 1;
  const isGroupByDisabledForDuration =
    isDurationMetric && !hasExactlyOneDurationMetricSelected;

  // For token usage metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneUsageMetricSelected = usageMetrics.length === 1;
  const isGroupByDisabledForUsage =
    isTokenUsageMetric && !hasExactlyOneUsageMetricSelected;

  const hasBreakdownField = breakdown.field !== BREAKDOWN_FIELD.NONE;

  const form = useForm<ProjectMetricsWidgetFormData>({
    resolver: zodResolver(ProjectMetricsWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      metricType,
      chartType,
      projectId: localProjectId,
      traceFilters,
      threadFilters,
      spanFilters,
      feedbackScores,
      durationMetrics,
      usageMetrics,
      breakdown,
      overrideDefaults,
    },
  });

  const currentFilters = isTraceMetric
    ? form.watch("traceFilters") || []
    : isThreadMetric
      ? form.watch("threadFilters") || []
      : form.watch("spanFilters") || [];

  useEffect(() => {
    if (isTraceMetric && form.formState.errors.traceFilters) {
      form.clearErrors("traceFilters");
    }
    if (isThreadMetric && form.formState.errors.threadFilters) {
      form.clearErrors("threadFilters");
    }
    if (isSpanMetric && form.formState.errors.spanFilters) {
      form.clearErrors("spanFilters");
    }
  }, [
    currentFilters.length,
    form,
    isTraceMetric,
    isThreadMetric,
    isSpanMetric,
  ]);

  useImperativeHandle(ref, () => ({
    submit: async () => {
      return await form.trigger();
    },
    isValid: form.formState.isValid,
  }));

  const handleMetricTypeChange = (value: string) => {
    // Determine if this is a duration or token usage metric for defaults
    const isDuration =
      value === METRIC_NAME_TYPE.TRACE_DURATION ||
      value === METRIC_NAME_TYPE.THREAD_DURATION ||
      value === METRIC_NAME_TYPE.SPAN_DURATION;
    const isUsage =
      value === METRIC_NAME_TYPE.TOKEN_USAGE ||
      value === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

    // Clear all filters, group by, and sub-metric selections when metric type changes
    // Also set defaults for duration and token usage metrics
    form.setValue("breakdown.field", BREAKDOWN_FIELD.NONE);
    form.setValue("breakdown.metadataKey", undefined);
    form.setValue("traceFilters", []);
    form.setValue("threadFilters", []);
    form.setValue("spanFilters", []);
    form.setValue("feedbackScores", []);
    form.setValue("durationMetrics", isDuration ? ["p50"] : []);
    form.setValue("usageMetrics", isUsage ? ["total_tokens"] : []);

    updatePreviewWidget({
      config: {
        ...config,
        metricType: value,
        traceFilters: [],
        threadFilters: [],
        spanFilters: [],
        feedbackScores: [],
        durationMetrics: isDuration ? ["p50"] : [],
        usageMetrics: isUsage ? ["total_tokens"] : [],
        breakdown: { field: BREAKDOWN_FIELD.NONE },
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
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = feedbackScores.length === 1;
    const isExactlyOne = newFeedbackScores.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        feedbackScores: newFeedbackScores,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleDurationMetricsChange = (newDurationMetrics: string[]) => {
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = durationMetrics.length === 1;
    const isExactlyOne = newDurationMetrics.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        durationMetrics: newDurationMetrics,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleUsageMetricsChange = (newUsageMetrics: string[]) => {
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = usageMetrics.length === 1;
    const isExactlyOne = newUsageMetrics.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        usageMetrics: newUsageMetrics,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleBreakdownChange = (newBreakdown: Partial<BreakdownConfig>) => {
    const updatedBreakdown = {
      ...breakdown,
      ...newBreakdown,
    };
    updatePreviewWidget({
      config: {
        ...config,
        breakdown: updatedBreakdown,
      },
    });
  };

  return (
    <Form {...form}>
      <WidgetEditorBaseLayout>
        <div className="space-y-4">
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
                            : metricType ===
                                METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES
                              ? ScoreSource.SPANS
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

          {isDurationMetric && (
            <FormField
              control={form.control}
              name="durationMetrics"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, [
                  "durationMetrics",
                ]);
                return (
                  <FormItem>
                    <FormLabel>Duration metrics</FormLabel>
                    <FormControl>
                      <LoadableSelectBox
                        buttonClassName={cn("w-full", {
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value || []}
                        onChange={(value) => {
                          field.onChange(value);
                          handleDurationMetricsChange(value);
                        }}
                        options={DURATION_METRIC_OPTIONS}
                        placeholder="All percentiles"
                        multiselect
                        showSelectAll
                        selectAllLabel="All percentiles"
                      />
                    </FormControl>
                    <Description>
                      Select specific duration percentiles to display. Leave
                      empty to show all percentiles.
                    </Description>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          )}

          {isTokenUsageMetric && projectId && (
            <FormField
              control={form.control}
              name="usageMetrics"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, [
                  "usageMetrics",
                ]);
                return (
                  <FormItem>
                    <FormLabel>Usage metrics</FormLabel>
                    <FormControl>
                      <LoadableSelectBox
                        buttonClassName={cn("w-full", {
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value || []}
                        onChange={(value) => {
                          field.onChange(value);
                          handleUsageMetricsChange(value);
                        }}
                        options={usageKeyOptions}
                        isLoading={isLoadingUsageKeys}
                        placeholder="All usage metrics"
                        multiselect
                        showSelectAll
                        selectAllLabel="All usage metrics"
                      />
                    </FormControl>
                    <Description>
                      Select specific usage metrics to display. Leave empty to
                      show all usage metrics.
                    </Description>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          )}

          <ProjectWidgetFiltersSection
            control={form.control}
            fieldName={
              isTraceMetric
                ? "traceFilters"
                : isThreadMetric
                  ? "threadFilters"
                  : "spanFilters"
            }
            projectId={projectId}
            filterType={
              isTraceMetric ? "trace" : isThreadMetric ? "thread" : "span"
            }
            onFiltersChange={(filters) => {
              updatePreviewWidget({
                config: {
                  ...config,
                  ...(isTraceMetric
                    ? { traceFilters: filters }
                    : isThreadMetric
                      ? { threadFilters: filters }
                      : { spanFilters: filters }),
                },
              });
            }}
          />

          <ProjectMetricsBreakdownSection
            control={form.control}
            metricType={metricType}
            projectId={projectId}
            isSpanMetric={!!isSpanMetric}
            breakdown={breakdown}
            isGroupByDisabledForFeedbackScore={
              isGroupByDisabledForFeedbackScore
            }
            isGroupByDisabledForDuration={isGroupByDisabledForDuration}
            isGroupByDisabledForUsage={isGroupByDisabledForUsage}
            onBreakdownChange={handleBreakdownChange}
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

          <WidgetOverrideDefaultsSection
            value={form.watch("overrideDefaults") || false}
            onChange={(value) => {
              form.setValue("overrideDefaults", value);
              updatePreviewWidget({
                config: {
                  ...config,
                  overrideDefaults: value,
                },
              });
            }}
            description="Turn this on to override the dashboard's default project for this widget."
          >
            <FormField
              control={form.control}
              name="projectId"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, ["projectId"]);
                return (
                  <FormItem>
                    <FormLabel>Project override</FormLabel>
                    <FormControl>
                      <ProjectsSelectBox
                        className={cn("flex-1", {
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value || ""}
                        onValueChange={(value) => {
                          field.onChange(value);
                          handleProjectChange(value);
                        }}
                        showClearButton
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          </WidgetOverrideDefaultsSection>
        </div>
      </WidgetEditorBaseLayout>
    </Form>
  );
});

ProjectMetricsEditor.displayName = "ProjectMetricsEditor";

export default ProjectMetricsEditor;
