import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
  useState,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Plus, X } from "lucide-react";

import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import WidgetOverrideDefaultsSection from "@/components/shared/Dashboard/widgets/shared/WidgetOverrideDefaultsSection/WidgetOverrideDefaultsSection";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

import { cn } from "@/lib/utils";
import {
  useDashboardStore,
  selectMixedConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";

import get from "lodash/get";

import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
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
import {
  BREAKDOWN_FIELD,
  BREAKDOWN_FIELD_LABELS,
  getCompatibleBreakdownFields,
} from "@/constants/breakdown";

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

  const breakdown = useMemo(
    () =>
      config.breakdown || {
        field: BREAKDOWN_FIELD.NONE,
      },
    [config.breakdown],
  );

  const globalProjectId = useDashboardStore((state) => {
    const config = selectMixedConfig(state);
    return config?.projectIds?.[0];
  });
  const projectId = localProjectId || globalProjectId || "";

  const selectedMetric = METRIC_OPTIONS.find((m) => m.value === metricType);
  const isTraceMetric = !metricType || selectedMetric?.filterType === "trace";
  const isThreadMetric = metricType && selectedMetric?.filterType === "thread";
  const isSpanMetric = metricType && selectedMetric?.filterType === "span";
  const isFeedbackScoreMetric =
    metricType === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES;

  // For feedback score metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneMetricSelected = feedbackScores.length === 1;
  const isGroupByDisabledForFeedbackScore =
    isFeedbackScoreMetric && !hasExactlyOneMetricSelected;

  const isMetadataBreakdown = breakdown.field === BREAKDOWN_FIELD.METADATA;
  const hasBreakdownField = breakdown.field !== BREAKDOWN_FIELD.NONE;

  // Local state to track if the group row UI should be shown
  // This allows showing the dropdown without immediately triggering a BE call
  const [showGroupRow, setShowGroupRow] = useState(hasBreakdownField);

  // Sync showGroupRow with actual breakdown state when breakdown changes externally
  useEffect(() => {
    if (hasBreakdownField) {
      setShowGroupRow(true);
    }
  }, [hasBreakdownField]);

  // Get compatible breakdown fields for the current metric type (excluding NONE)
  const compatibleBreakdownFields = useMemo(() => {
    if (!metricType) return [];
    return getCompatibleBreakdownFields(metricType).filter(
      (field) => field !== BREAKDOWN_FIELD.NONE,
    );
  }, [metricType]);

  const breakdownFieldOptions = useMemo(() => {
    return compatibleBreakdownFields.map((field) => ({
      value: field,
      label: BREAKDOWN_FIELD_LABELS[field],
    }));
  }, [compatibleBreakdownFields]);

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
    // Check if current breakdown field is compatible with the new metric type
    const newCompatibleFields = getCompatibleBreakdownFields(value);
    const currentBreakdownField = breakdown.field;

    // Reset breakdown if current field is not compatible with new metric type
    const shouldResetBreakdown =
      currentBreakdownField !== BREAKDOWN_FIELD.NONE &&
      !newCompatibleFields.includes(currentBreakdownField);

    updatePreviewWidget({
      config: {
        ...config,
        metricType: value,
        ...(shouldResetBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
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

    // Also hide the group row if breakdown is being cleared
    if (shouldClearBreakdown) {
      setShowGroupRow(false);
    }
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

  const handleAddGroup = () => {
    // Only show the group row UI without setting a breakdown field
    // The preview won't update until a field is actually selected
    setShowGroupRow(true);
  };

  const handleRemoveGroup = () => {
    setShowGroupRow(false);
    // Reset the form field values so they don't retain the previous selection
    form.setValue("breakdown.field", BREAKDOWN_FIELD.NONE);
    form.setValue("breakdown.metadataKey", undefined);
    handleBreakdownChange({
      field: BREAKDOWN_FIELD.NONE,
      metadataKey: undefined,
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

          {/* Group by Section - matches experiment widget pattern */}
          <Accordion type="single" collapsible className="w-full">
            <AccordionItem value="groupby" className="">
              <AccordionTrigger className="h-11 py-1.5 hover:no-underline">
                Group by {hasBreakdownField && "(1)"}
              </AccordionTrigger>
              <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
                <Description>Add groups to aggregate data.</Description>
                <div className="space-y-3">
                  {isGroupByDisabledForFeedbackScore ? (
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled
                        className="w-fit"
                      >
                        <Plus className="mr-1 size-3.5" />
                        Add group
                      </Button>
                      <ExplainerIcon
                        {...EXPLAINERS_MAP[
                          EXPLAINER_ID.feedback_score_groupby_requires_single_metric
                        ]}
                      />
                    </div>
                  ) : showGroupRow ? (
                    <>
                      {/* Group row with field selector and remove button */}
                      <div className="flex items-start gap-2">
                        <span className="comet-body-s flex h-8 items-center pr-2">
                          By
                        </span>
                        <FormField
                          control={form.control}
                          name="breakdown.field"
                          render={({ field, formState }) => {
                            const validationErrors = get(formState.errors, [
                              "breakdown",
                              "field",
                            ]);
                            return (
                              <FormItem className="min-w-40">
                                <FormControl>
                                  <SelectBox
                                    className={cn({
                                      "border-destructive": Boolean(
                                        validationErrors?.message,
                                      ),
                                    })}
                                    value={
                                      field.value === BREAKDOWN_FIELD.NONE
                                        ? ""
                                        : field.value || ""
                                    }
                                    onChange={(value) => {
                                      field.onChange(value);
                                      handleBreakdownChange({
                                        field: value as BREAKDOWN_FIELD,
                                        metadataKey:
                                          value === BREAKDOWN_FIELD.METADATA
                                            ? breakdown.metadataKey
                                            : undefined,
                                      });
                                    }}
                                    options={breakdownFieldOptions}
                                    placeholder="Select field"
                                  />
                                </FormControl>
                                <FormMessage />
                              </FormItem>
                            );
                          }}
                        />
                        {/* Configuration key input when Configuration field is selected */}
                        {isMetadataBreakdown && (
                          <FormField
                            control={form.control}
                            name="breakdown.metadataKey"
                            render={({ field, formState }) => {
                              const validationErrors = get(formState.errors, [
                                "breakdown",
                                "metadataKey",
                              ]);
                              return (
                                <FormItem className="min-w-32 max-w-[30vw] flex-1">
                                  <FormControl>
                                    <TracesOrSpansPathsAutocomplete
                                      hasError={Boolean(
                                        validationErrors?.message,
                                      )}
                                      rootKeys={["metadata"]}
                                      projectId={projectId}
                                      type={
                                        isSpanMetric
                                          ? TRACE_DATA_TYPE.spans
                                          : TRACE_DATA_TYPE.traces
                                      }
                                      placeholder="key"
                                      excludeRoot={true}
                                      value={field.value || ""}
                                      onValueChange={(value) => {
                                        field.onChange(value);
                                        handleBreakdownChange({
                                          metadataKey: value,
                                        });
                                      }}
                                    />
                                  </FormControl>
                                  <FormMessage />
                                </FormItem>
                              );
                            }}
                          />
                        )}
                        <Button
                          type="button"
                          variant="minimal"
                          size="icon-xs"
                          onClick={handleRemoveGroup}
                          className="mt-1.5"
                        >
                          <X className="size-4" />
                        </Button>
                      </div>
                    </>
                  ) : (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={handleAddGroup}
                      className="w-fit"
                    >
                      <Plus className="mr-1 size-3.5" />
                      Add group
                    </Button>
                  )}
                </div>
              </AccordionContent>
            </AccordionItem>
          </Accordion>

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
