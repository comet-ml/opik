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
import { Description } from "@/components/ui/description";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import {
  useDashboardStore,
  selectMixedConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import WidgetOverrideDefaultsSection from "@/components/shared/Dashboard/widgets/shared/WidgetOverrideDefaultsSection/WidgetOverrideDefaultsSection";
import {
  ProjectStatsCardWidgetSchema,
  ProjectStatsCardWidgetFormData,
} from "./schema";
import { getAllMetricOptions } from "./metrics";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";

const SOURCE_OPTIONS = [
  { value: TRACE_DATA_TYPE.traces, label: "Traces statistics" },
  { value: TRACE_DATA_TYPE.spans, label: "Spans statistics" },
];

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

  const globalProjectId = useDashboardStore((state) => {
    const config = selectMixedConfig(state);
    return config?.projectIds?.[0];
  });
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

  const overrideDefaults = config.overrideDefaults || false;

  const form = useForm<ProjectStatsCardWidgetFormData>({
    resolver: zodResolver(ProjectStatsCardWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      source,
      metric,
      projectId: localProjectId,
      traceFilters,
      spanFilters,
      overrideDefaults,
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
      <WidgetEditorBaseLayout>
        <div className="space-y-4">
          <FormField
            control={form.control}
            name="source"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["source"]);
              return (
                <FormItem>
                  <FormLabel>Statistics source</FormLabel>
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
              const placeholder = isPending
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
                      disabled={isPending}
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

ProjectStatsCardEditor.displayName = "ProjectStatsCardEditor";

export default ProjectStatsCardEditor;
