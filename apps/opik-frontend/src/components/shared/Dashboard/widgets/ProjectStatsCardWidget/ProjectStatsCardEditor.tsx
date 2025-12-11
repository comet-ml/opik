import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { get } from "lodash";
import { cn } from "@/lib/utils";

import {
  AddWidgetConfig,
  ProjectStatsCardWidget,
  WidgetEditorHandle,
} from "@/types/dashboard";
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
import { useDashboardStore } from "@/store/DashboardStore";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import {
  ProjectStatsCardWidgetSchema,
  ProjectStatsCardWidgetFormData,
} from "./schema";
import { getAllMetricOptions } from "./metrics";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import { TRACE_DATA_TYPE } from "@/constants/traces";

type ProjectStatsCardEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

const SOURCE_OPTIONS = [
  { value: TRACE_DATA_TYPE.traces, label: "Traces stats" },
  { value: TRACE_DATA_TYPE.spans, label: "Spans stats" },
];

const ProjectStatsCardEditor = forwardRef<
  WidgetEditorHandle,
  ProjectStatsCardEditorProps
>(({ title, subtitle, config, onChange }, ref) => {
  const widgetConfig = config as ProjectStatsCardWidget["config"];
  const source = widgetConfig?.source || TRACE_DATA_TYPE.traces;
  const metric = widgetConfig?.metric || "";
  const localProjectId = widgetConfig?.projectId;

  const traceFilters = useMemo(
    () => widgetConfig?.traceFilters || [],
    [widgetConfig?.traceFilters],
  );
  const spanFilters = useMemo(
    () => widgetConfig?.spanFilters || [],
    [widgetConfig?.spanFilters],
  );

  const globalProjectId = useDashboardStore(
    (state) => state.config?.projectIds?.[0],
  );
  const projectId = localProjectId || globalProjectId || "";
  const isTraceSource = source === TRACE_DATA_TYPE.traces;

  const { data, isPending } = useTracesOrSpansScoresColumns(
    {
      projectId,
      type: source,
    },
    {},
  );

  const metricOptions = useMemo(() => {
    const scoreNames = data?.scores.map((s) => s.name) || [];
    return getAllMetricOptions(source, scoreNames);
  }, [source, data?.scores]);

  const form = useForm<ProjectStatsCardWidgetFormData>({
    resolver: zodResolver(ProjectStatsCardWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      title,
      subtitle: subtitle || "",
      source,
      metric,
      projectId: localProjectId,
      traceFilters,
      spanFilters,
    },
  });

  const currentFilters = isTraceSource
    ? form.watch("traceFilters") || []
    : form.watch("spanFilters") || [];

  useEffect(() => {
    const filterKey = isTraceSource ? "traceFilters" : "spanFilters";
    if (form.formState.errors[filterKey]) {
      form.clearErrors(filterKey);
    }
  }, [currentFilters.length, form, isTraceSource]);

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
            source: values.source,
            projectId: values.projectId,
            metric: values.metric,
            traceFilters: values.traceFilters,
            spanFilters: values.spanFilters,
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

  const handleSourceChange = (value: string) => {
    onChange({
      config: {
        ...config,
        source: value as TRACE_DATA_TYPE,
      },
    });
  };

  const handleProjectChange = (projectId: string) => {
    onChange({
      config: {
        ...config,
        projectId,
      },
    });
  };

  const handleMetricChange = (value: string) => {
    onChange({
      config: {
        ...config,
        metric: value,
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
                      value={field.value || ""}
                      onValueChange={(value) => {
                        field.onChange(value);
                        handleProjectChange(value);
                      }}
                    />
                  </FormControl>
                  <Description>
                    Pick the project that contains the data you want to
                    visualize.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <FormField
            control={form.control}
            name="source"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["source"]);
              return (
                <FormItem>
                  <FormLabel>Stats source</FormLabel>
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
                        handleSourceChange(value);
                      }}
                      options={SOURCE_OPTIONS}
                      placeholder="Select source"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <FormField
            control={form.control}
            name="metric"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["metric"]);
              const placeholder = !projectId
                ? "Select a project first"
                : isPending
                  ? "Loading available metrics..."
                  : "Select a metric";

              return (
                <FormItem>
                  <FormLabel>Metric</FormLabel>
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
                        handleMetricChange(value);
                      }}
                      options={metricOptions}
                      placeholder={placeholder}
                      disabled={isPending || !projectId}
                    />
                  </FormControl>
                  <Description>
                    Select the metric you want this widget to display.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <ProjectWidgetFiltersSection
            control={form.control}
            fieldName={isTraceSource ? "traceFilters" : "spanFilters"}
            projectId={projectId}
            filterType={isTraceSource ? "trace" : "span"}
            onFiltersChange={(filters) => {
              onChange({
                config: {
                  ...config,
                  ...(isTraceSource
                    ? { traceFilters: filters }
                    : { spanFilters: filters }),
                },
              });
            }}
          />
        </div>
      </div>
    </Form>
  );
});

ProjectStatsCardEditor.displayName = "ProjectStatsCardEditor";

export default ProjectStatsCardEditor;
