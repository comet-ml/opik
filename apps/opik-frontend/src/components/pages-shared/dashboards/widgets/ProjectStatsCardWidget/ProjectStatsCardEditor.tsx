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
  DashboardWidget,
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
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/pages-shared/dashboards/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import {
  SOURCE_OPTIONS,
  renderSourceOption,
  renderSourceTrigger,
} from "@/lib/sourceTypeSelect";
import {
  ProjectStatsCardWidgetSchema,
  ProjectStatsCardWidgetFormData,
} from "./schema";
import { getAllMetricOptions } from "./metrics";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import { TRACE_DATA_TYPE } from "@/constants/traces";

const ProjectStatsCardEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & ProjectStatsCardWidget;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  const { config } = widgetData;
  const source = config.source || TRACE_DATA_TYPE.traces;
  const metric = config.metric || "";
  const localProjectId = config.projectId;

  const traceFilters = useMemo(
    () => config.traceFilters || [],
    [config.traceFilters],
  );
  const spanFilters = useMemo(
    () => config.spanFilters || [],
    [config.spanFilters],
  );

  const runtimeContext = useDashboardStore((state) => {
    const rc = selectRuntimeConfig(state);
    return {
      projectId: rc?.projectIds?.[0],
    };
  });
  const hasRuntimeProjectId = !!runtimeContext.projectId;
  const projectId = runtimeContext.projectId || localProjectId || "";
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
      source,
      metric,
      projectId,
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
      return await form.trigger();
    },
    isValid: form.formState.isValid,
  }));

  const handleSourceChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        source: value as TRACE_DATA_TYPE,
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

  const handleMetricChange = (value: string) => {
    updatePreviewWidget({
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
          name="projectId"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["projectId"]);
            return (
              <FormItem>
                <FormLabel>Project</FormLabel>
                <FormControl>
                  <ProjectsSelectBox
                    className={cn("flex-1", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    value={field.value || ""}
                    onValueChange={(value) => {
                      field.onChange(value);
                      handleProjectChange(value);
                    }}
                    disabled={hasRuntimeProjectId}
                  />
                </FormControl>
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
                <FormLabel>Source</FormLabel>
                <FormControl>
                  <SelectBox
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    value={field.value}
                    onChange={(value) => {
                      field.onChange(value);
                      handleSourceChange(value);
                    }}
                    options={SOURCE_OPTIONS}
                    placeholder="Select source"
                    renderOption={renderSourceOption}
                    renderTrigger={renderSourceTrigger}
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
            const placeholder = isPending
              ? "Loading available metrics..."
              : "Select a metric";

            return (
              <FormItem>
                <FormLabel>Metric type</FormLabel>
                <FormControl>
                  <LoadableSelectBox
                    buttonClassName={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    value={field.value}
                    onChange={(value) => {
                      field.onChange(value);
                      handleMetricChange(value);
                    }}
                    options={metricOptions}
                    placeholder={placeholder}
                    disabled={isPending}
                  />
                </FormControl>
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
            updatePreviewWidget({
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
    </Form>
  );
});

ProjectStatsCardEditor.displayName = "ProjectStatsCardEditor";

export default ProjectStatsCardEditor;
