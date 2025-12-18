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
import { Input } from "@/components/ui/input";
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
  AddWidgetConfig,
  EXPERIMENT_DATA_SOURCE,
  ExperimentsFeedbackScoresWidgetType,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  ExperimentsFeedbackScoresWidgetSchema,
  ExperimentsFeedbackScoresWidgetFormData,
} from "./schema";

type ExperimentsFeedbackScoresWidgetEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

const CHART_TYPE_OPTIONS = [
  { value: CHART_TYPE.line, label: "Line chart" },
  { value: CHART_TYPE.bar, label: "Bar chart" },
  { value: CHART_TYPE.radar, label: "Radar chart" },
];

const ExperimentsFeedbackScoresWidgetEditor = forwardRef<
  WidgetEditorHandle,
  ExperimentsFeedbackScoresWidgetEditorProps
>(({ title, subtitle, config, onChange }, ref) => {
  const widgetConfig = config as ExperimentsFeedbackScoresWidgetType["config"];

  const dataSource = (widgetConfig?.dataSource ||
    EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP) as EXPERIMENT_DATA_SOURCE;

  const filters = useMemo(
    () => widgetConfig?.filters || [],
    [widgetConfig?.filters],
  );

  const groups = useMemo(
    () => widgetConfig?.groups || [],
    [widgetConfig?.groups],
  );

  const experimentIds = useMemo(
    () => widgetConfig?.experimentIds || [],
    [widgetConfig?.experimentIds],
  );

  const chartType = widgetConfig?.chartType || CHART_TYPE.line;

  const feedbackScores = useMemo(
    () => widgetConfig?.feedbackScores || [],
    [widgetConfig?.feedbackScores],
  );

  const form = useForm<ExperimentsFeedbackScoresWidgetFormData>({
    resolver: zodResolver(ExperimentsFeedbackScoresWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      title,
      subtitle: subtitle || "",
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
      const isValid = await form.trigger();
      if (isValid) {
        const values = form.getValues();
        onChange({
          title: values.title,
          subtitle: values.subtitle,
          config: {
            ...config,
            dataSource: values.dataSource,
            filters: values.filters,
            groups: values.groups,
            experimentIds: values.experimentIds,
            chartType: values.chartType,
            feedbackScores: values.feedbackScores,
          },
        });
      }
      return isValid;
    },
    isValid: form.formState.isValid,
  }));

  const handleTitleChange = (value: string) => {
    onChange({ title: value });
  };

  const handleSubtitleChange = (value: string) => {
    onChange({ subtitle: value });
  };

  const handleDataSourceChange = (value: string) => {
    const newDataSource = value as EXPERIMENT_DATA_SOURCE;
    form.setValue("dataSource", newDataSource);
    onChange({
      config: {
        ...config,
        dataSource: newDataSource,
      },
    });
  };

  const handleChartTypeChange = (value: string) => {
    form.setValue("chartType", value as CHART_TYPE);
    onChange({
      config: {
        ...config,
        chartType: value as CHART_TYPE,
      },
    });
  };

  const handleFiltersChange = (newFilters: Filters) => {
    onChange({
      config: {
        ...config,
        filters: newFilters,
      },
    });
  };

  const handleGroupsChange = (newGroups: Groups) => {
    onChange({
      config: {
        ...config,
        groups: newGroups,
      },
    });
  };

  const handleExperimentIdsChange = (newExperimentIds: string[]) => {
    form.setValue("experimentIds", newExperimentIds);
    onChange({
      config: {
        ...config,
        experimentIds: newExperimentIds,
      },
    });
  };

  const handleFeedbackScoresChange = (newFeedbackScores: string[]) => {
    form.setValue("feedbackScores", newFeedbackScores);
    onChange({
      config: {
        ...config,
        feedbackScores: newFeedbackScores,
      },
    });
  };

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="title"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["title"]);
            return (
              <FormItem>
                <FormLabel>Widget title</FormLabel>
                <FormControl>
                  <Input
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder="Enter widget title"
                    {...field}
                    onChange={(e) => {
                      field.onChange(e);
                      handleTitleChange(e.target.value);
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />

        <FormField
          control={form.control}
          name="subtitle"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Widget subtitle (optional)</FormLabel>
              <FormControl>
                <Input
                  placeholder="Enter widget subtitle"
                  {...field}
                  onChange={(e) => {
                    field.onChange(e);
                    handleSubtitleChange(e.target.value);
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h3 className="comet-body-accented">Widget settings</h3>
            <Description>
              Configure the data source and visualization options for this
              widget.
            </Description>
          </div>

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
                    Select specific metrics to display. Leave empty to show all
                    available metrics.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        </div>
      </div>
    </Form>
  );
});

ExperimentsFeedbackScoresWidgetEditor.displayName =
  "ExperimentsFeedbackScoresWidgetEditor";

export default ExperimentsFeedbackScoresWidgetEditor;
