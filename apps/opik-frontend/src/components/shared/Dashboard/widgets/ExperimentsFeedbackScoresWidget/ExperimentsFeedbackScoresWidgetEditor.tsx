import React, {
  useMemo,
  forwardRef,
  useImperativeHandle,
  useEffect,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";

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
import ExperimentWidgetDataSection from "@/components/shared/Dashboard/widgets/shared/ExperimentWidgetDataSection/ExperimentWidgetDataSection";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Filter, ListChecks } from "lucide-react";

import { cn } from "@/lib/utils";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { CHART_TYPE } from "@/constants/chart";

import {
  DashboardWidget,
  EXPERIMENT_DATA_SOURCE,
  ExperimentsFeedbackScoresWidgetType,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import {
  ExperimentsFeedbackScoresWidgetSchema,
  ExperimentsFeedbackScoresWidgetFormData,
} from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";

const CHART_TYPE_OPTIONS = [
  { value: CHART_TYPE.line, label: "Line chart" },
  { value: CHART_TYPE.bar, label: "Bar chart" },
  { value: CHART_TYPE.radar, label: "Radar chart" },
];

const ExperimentsFeedbackScoresWidgetEditor = forwardRef<WidgetEditorHandle>(
  (_, ref) => {
    const widgetData = useDashboardStore(
      (state) => state.previewWidget!,
    ) as DashboardWidget & ExperimentsFeedbackScoresWidgetType;
    const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

    const { config } = widgetData;

    const dataSource =
      config.dataSource || EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP;

    const filters = useMemo(() => config.filters || [], [config.filters]);

    const groups = useMemo(() => config.groups || [], [config.groups]);

    const experimentIds = useMemo(
      () => config.experimentIds || [],
      [config.experimentIds],
    );

    const chartType = config.chartType || CHART_TYPE.line;

    const feedbackScores = useMemo(
      () => config.feedbackScores || [],
      [config.feedbackScores],
    );

    const form = useForm<ExperimentsFeedbackScoresWidgetFormData>({
      resolver: zodResolver(ExperimentsFeedbackScoresWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        dataSource,
        filters,
        groups,
        experimentIds,
        chartType,
        feedbackScores,
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

    const handleDataSourceChange = (value: string) => {
      const newDataSource = value as EXPERIMENT_DATA_SOURCE;
      form.setValue("dataSource", newDataSource);
      updatePreviewWidget({
        config: {
          ...config,
          dataSource: newDataSource,
        },
      });
    };

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

    const handleExperimentIdsChange = (newExperimentIds: string[]) => {
      form.setValue("experimentIds", newExperimentIds);
      updatePreviewWidget({
        config: {
          ...config,
          experimentIds: newExperimentIds,
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

    return (
      <Form {...form}>
        <WidgetEditorBaseLayout>
          <div className="space-y-4">
            <FormField
              control={form.control}
              name="dataSource"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Data source</FormLabel>
                  <FormControl>
                    <ToggleGroup
                      type="single"
                      variant="ghost"
                      value={
                        field.value || EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
                      }
                      onValueChange={(value) => {
                        if (value) {
                          field.onChange(value);
                          handleDataSourceChange(value);
                        }
                      }}
                      className="w-fit justify-start"
                    >
                      <ToggleGroupItem
                        value={EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP}
                        aria-label="Filter experiments"
                        className="gap-1.5"
                      >
                        <Filter className="size-3.5" />
                        <span>Filter experiments</span>
                      </ToggleGroupItem>
                      <ToggleGroupItem
                        value={EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS}
                        aria-label="Manual selection"
                        className="gap-1.5"
                      >
                        <ListChecks className="size-3.5" />
                        <span>Manual selection</span>
                      </ToggleGroupItem>
                    </ToggleGroup>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {form.watch("dataSource") ===
              EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP && (
              <ExperimentWidgetDataSection
                control={form.control}
                filtersFieldName="filters"
                groupsFieldName="groups"
                filters={filters}
                groups={groups}
                onFiltersChange={handleFiltersChange}
                onGroupsChange={handleGroupsChange}
              />
            )}

            {form.watch("dataSource") ===
              EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS && (
              <FormField
                control={form.control}
                name="experimentIds"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "experimentIds",
                  ]);
                  return (
                    <FormItem>
                      <FormLabel>Manual selection</FormLabel>
                      <FormControl>
                        <ExperimentsSelectBox
                          value={field.value || []}
                          onValueChange={(value) => {
                            field.onChange(value);
                            handleExperimentIdsChange(value);
                          }}
                          multiselect
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                        />
                      </FormControl>
                      <Description>
                        Widgets use the dashboard&apos;s experiment settings by
                        default. You can override them here and select different
                        experiments.
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            )}

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
                        value={field.value || CHART_TYPE.line}
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
                        entityIds={experimentIds}
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
          </div>
        </WidgetEditorBaseLayout>
      </Form>
    );
  },
);

ExperimentsFeedbackScoresWidgetEditor.displayName =
  "ExperimentsFeedbackScoresWidgetEditor";

export default ExperimentsFeedbackScoresWidgetEditor;
