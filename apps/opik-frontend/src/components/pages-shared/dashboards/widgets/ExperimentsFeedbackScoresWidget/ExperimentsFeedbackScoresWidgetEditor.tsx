import React, {
  useMemo,
  forwardRef,
  useImperativeHandle,
  useEffect,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";

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

import VisualizationCardSelector from "@/components/pages-shared/dashboards/widgets/shared/VisualizationCardSelector/VisualizationCardSelector";
import ExperimentWidgetDataSection from "@/components/pages-shared/dashboards/widgets/shared/ExperimentWidgetDataSection/ExperimentWidgetDataSection";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import { cn } from "@/lib/utils";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { CHART_TYPE } from "@/constants/chart";
import { extractExperimentIdsFilter } from "@/lib/filters";

import {
  DashboardWidget,
  ExperimentsFeedbackScoresWidgetType,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdatePreviewWidget,
  selectRuntimeConfig,
} from "@/store/DashboardStore";
import {
  ExperimentsFeedbackScoresWidgetSchema,
  ExperimentsFeedbackScoresWidgetFormData,
} from "./schema";
import {
  MAX_MAX_EXPERIMENTS,
  MIN_MAX_EXPERIMENTS,
} from "@/lib/dashboard/utils";

const ExperimentsFeedbackScoresWidgetEditor = forwardRef<WidgetEditorHandle>(
  (_, ref) => {
    const widgetData = useDashboardStore(
      (state) => state.previewWidget!,
    ) as DashboardWidget & ExperimentsFeedbackScoresWidgetType;
    const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);
    const runtimeConfig = useDashboardStore(selectRuntimeConfig);

    const { config } = widgetData;

    const widgetFilters = useMemo(() => config.filters || [], [config.filters]);

    const widgetGroups = useMemo(() => config.groups || [], [config.groups]);

    const widgetMaxExperimentsCount =
      config.maxExperimentsCount ?? MAX_MAX_EXPERIMENTS;

    const chartType = config.chartType || CHART_TYPE.bar;

    const feedbackScores = useMemo(
      () => config.feedbackScores || [],
      [config.feedbackScores],
    );

    const hasRuntimeExperiments =
      (runtimeConfig?.experimentIds?.length ?? 0) > 0;

    const computedExperimentIds = useMemo(() => {
      if (hasRuntimeExperiments) {
        return runtimeConfig?.experimentIds || [];
      }
      const { experimentIds } = extractExperimentIdsFilter(widgetFilters);
      return experimentIds;
    }, [hasRuntimeExperiments, runtimeConfig?.experimentIds, widgetFilters]);

    const form = useForm<ExperimentsFeedbackScoresWidgetFormData>({
      resolver: zodResolver(ExperimentsFeedbackScoresWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        filters: widgetFilters,
        groups: widgetGroups,
        chartType,
        feedbackScores,
        maxExperimentsCount:
          widgetMaxExperimentsCount !== undefined
            ? String(widgetMaxExperimentsCount)
            : undefined,
      },
    });

    const currentFilters = form.watch("filters") || [];

    useEffect(() => {
      if (form.formState.errors.filters) {
        form.clearErrors("filters");
      }
    }, [currentFilters.length, form]);

    useImperativeHandle(ref, () => ({
      submit: async () => {
        return await form.trigger();
      },
      isValid: form.formState.isValid,
    }));

    const handleChartTypeChange = (value: string) => {
      form.setValue("chartType", value as CHART_TYPE);
      updatePreviewWidget({
        config: {
          ...config,
          chartType: value as CHART_TYPE,
        },
      });
    };

    const handleFiltersChange = (newFilters: Filters) => {
      updatePreviewWidget({
        config: {
          ...config,
          filters: newFilters,
        },
      });
    };

    const handleGroupsChange = (newGroups: Groups) => {
      updatePreviewWidget({
        config: {
          ...config,
          groups: newGroups,
        },
      });
    };

    const handleFeedbackScoresChange = (newFeedbackScores: string[]) => {
      form.setValue("feedbackScores", newFeedbackScores);
      updatePreviewWidget({
        config: {
          ...config,
          feedbackScores: newFeedbackScores,
        },
      });
    };

    const handleMaxExperimentsCountChange = (value: string) => {
      form.setValue("maxExperimentsCount", value, {
        shouldValidate: true,
      });
      const numValue = isEmpty(value) ? undefined : parseInt(value, 10);
      updatePreviewWidget({
        config: {
          ...config,
          maxExperimentsCount: isNumber(numValue) ? numValue : undefined,
        },
      });
    };

    return (
      <Form {...form}>
        <div className="space-y-4">
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
                      scoreSource={ScoreSource.EXPERIMENTS}
                      entityIds={computedExperimentIds}
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
                    Select specific metrics to display. Leave empty to show all
                    available metrics.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          {hasRuntimeExperiments ? (
            <FormItem>
              <FormLabel>Data source</FormLabel>
              <Description>
                Experiments are provided by the page context.
              </Description>
            </FormItem>
          ) : (
            <>
              <ExperimentWidgetDataSection
                control={form.control}
                filtersFieldName="filters"
                groupsFieldName="groups"
                filters={widgetFilters}
                groups={widgetGroups}
                onFiltersChange={handleFiltersChange}
                onGroupsChange={handleGroupsChange}
              />
              <FormField
                control={form.control}
                name="maxExperimentsCount"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "maxExperimentsCount",
                  ]);
                  return (
                    <FormItem>
                      <FormLabel>Max experiments</FormLabel>
                      <FormControl>
                        <Input
                          type="number"
                          min={MIN_MAX_EXPERIMENTS}
                          max={MAX_MAX_EXPERIMENTS}
                          value={field.value ?? ""}
                          onChange={(e) => {
                            const value = e.target.value;
                            handleMaxExperimentsCountChange(value);
                          }}
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                        />
                      </FormControl>
                      <Description>
                        Limit how many experiments are loaded (max{" "}
                        {MAX_MAX_EXPERIMENTS}).
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            </>
          )}

          <FormField
            control={form.control}
            name="chartType"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Visualization</FormLabel>
                <FormControl>
                  <VisualizationCardSelector
                    value={field.value || CHART_TYPE.bar}
                    onChange={(value) => {
                      field.onChange(value);
                      handleChartTypeChange(value);
                    }}
                    types={[CHART_TYPE.bar, CHART_TYPE.line, CHART_TYPE.radar]}
                  />
                </FormControl>
              </FormItem>
            )}
          />
        </div>
      </Form>
    );
  },
);

ExperimentsFeedbackScoresWidgetEditor.displayName =
  "ExperimentsFeedbackScoresWidgetEditor";

export default ExperimentsFeedbackScoresWidgetEditor;
