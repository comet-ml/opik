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

import { cn } from "@/lib/utils";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { CHART_TYPE } from "@/constants/chart";

import {
  AddWidgetConfig,
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

  const filters = useMemo(
    () => widgetConfig?.filters || [],
    [widgetConfig?.filters],
  );

  const groups = useMemo(
    () => widgetConfig?.groups || [],
    [widgetConfig?.groups],
  );

  const chartType = widgetConfig?.chartType || CHART_TYPE.line;

  const form = useForm<ExperimentsFeedbackScoresWidgetFormData>({
    resolver: zodResolver(ExperimentsFeedbackScoresWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      title,
      subtitle: subtitle || "",
      filters,
      groups,
      chartType,
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
            filters: values.filters,
            groups: values.groups,
            chartType: values.chartType,
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

          <ExperimentWidgetDataSection
            control={form.control}
            filtersFieldName="filters"
            groupsFieldName="groups"
            filters={filters}
            groups={groups}
            onFiltersChange={handleFiltersChange}
            onGroupsChange={handleGroupsChange}
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
        </div>
      </div>
    </Form>
  );
});

ExperimentsFeedbackScoresWidgetEditor.displayName =
  "ExperimentsFeedbackScoresWidgetEditor";

export default ExperimentsFeedbackScoresWidgetEditor;
