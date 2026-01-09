import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
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
import { Input } from "@/components/ui/input";
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
  BreakdownConfig,
} from "@/types/dashboard";
import {
  ProjectMetricsWidgetSchema,
  ProjectMetricsWidgetFormData,
} from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";
import { CHART_TYPE } from "@/constants/chart";
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
  const isFeedbackScoreMetric =
    metricType === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES;

  const isMetadataBreakdown = breakdown.field === BREAKDOWN_FIELD.METADATA;
  const hasBreakdown = breakdown.field !== BREAKDOWN_FIELD.NONE;

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
      feedbackScores,
      breakdown,
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
    // Set a default breakdown field when "Add group" is clicked
    const defaultField =
      compatibleBreakdownFields.length > 0
        ? compatibleBreakdownFields[0]
        : BREAKDOWN_FIELD.TAGS;
    handleBreakdownChange({ field: defaultField, metadataKey: undefined });
  };

  const handleRemoveGroup = () => {
    handleBreakdownChange({ field: BREAKDOWN_FIELD.NONE, metadataKey: undefined });
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

          {/* Group by Section - matches experiment widget pattern */}
          <Accordion type="single" collapsible className="w-full">
            <AccordionItem value="groupby" className="border-t">
              <AccordionTrigger className="py-3 hover:no-underline">
                Group by {hasBreakdown && "(1)"}
              </AccordionTrigger>
              <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
                <Description>Add groups to aggregate data.</Description>
                <div className="space-y-3">
                  {hasBreakdown ? (
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
                              <FormItem className="flex-1">
                                <FormControl>
                                  <SelectBox
                                    className={cn({
                                      "border-destructive": Boolean(
                                        validationErrors?.message,
                                      ),
                                    })}
                                    value={field.value || ""}
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

                      {/* Metadata key input when metadata field is selected */}
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
                              <FormItem className="ml-8">
                                <FormLabel>Metadata key</FormLabel>
                                <FormControl>
                                  <Input
                                    className={cn({
                                      "border-destructive": Boolean(
                                        validationErrors?.message,
                                      ),
                                    })}
                                    value={field.value || ""}
                                    onChange={(e) => {
                                      field.onChange(e.target.value);
                                      handleBreakdownChange({
                                        metadataKey: e.target.value,
                                      });
                                    }}
                                    placeholder="e.g., agent_id, environment"
                                  />
                                </FormControl>
                                <Description>
                                  The key to extract from the metadata JSON for
                                  grouping.
                                </Description>
                                <FormMessage />
                              </FormItem>
                            );
                          }}
                        />
                      )}
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
        </div>
      </WidgetEditorBaseLayout>
    </Form>
  );
});

ProjectMetricsEditor.displayName = "ProjectMetricsEditor";

export default ProjectMetricsEditor;
