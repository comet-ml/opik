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
} from "@/ui/form";
import SelectBox from "@/shared/SelectBox/SelectBox";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import ProjectsSelectBox from "@/v1/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/v1/pages-shared/dashboards/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
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
import {
  getWorkspaceStatMetric,
  WORKSPACE_STAT_METRIC_OPTIONS,
  isMultiProjectSelection,
  DEFAULT_WORKSPACE_USAGE_METRIC,
} from "@/lib/dashboard/workspaceMetrics";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import useProjectTokenUsageNames from "@/api/projects/useProjectTokenUsageNames";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { TRACE_DATA_TYPE } from "@/constants/traces";

const ProjectStatsCardEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & ProjectStatsCardWidget;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  const { config } = widgetData;
  const source = config.source || TRACE_DATA_TYPE.traces;
  const metric = config.metric || "";
  const usageMetric = config.usageMetric || "";
  const localProjectId = config.projectId;
  const localProjectIds = useMemo<string[]>(
    () =>
      (config.projectIds as string[] | undefined) ??
      (localProjectId ? [localProjectId] : []),
    [config.projectIds, localProjectId],
  );

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
  // Representative project (runtime, else first selected) for loading option lists.
  const projectId = runtimeContext.projectId || localProjectIds[0] || "";
  const isTraceSource = source === TRACE_DATA_TYPE.traces;

  // Selecting more than one project aggregates across projects, which only supports span total metrics.
  const isMultiProject = isMultiProjectSelection(
    runtimeContext.projectId,
    localProjectIds,
  );
  const workspaceMetricDef = isMultiProject
    ? getWorkspaceStatMetric(metric)
    : null;
  const requiresUsageKey = Boolean(workspaceMetricDef?.requiresUsageKey);
  // Multi-project aggregates span metrics, so filters are always span filters there; single-project follows source.
  const useSpanFilters = isMultiProject || !isTraceSource;

  const { data, isPending } = useTracesOrSpansScoresColumns(
    {
      projectId,
      type: source,
    },
    { enabled: !isMultiProject && !!projectId },
  );

  const { data: tokenUsageNamesData } = useProjectTokenUsageNames(
    { projectId },
    { enabled: isMultiProject && requiresUsageKey && !!projectId },
  );

  const usageKeyOptions = useMemo(
    () =>
      (tokenUsageNamesData?.names ?? [])
        .slice()
        .sort((a, b) => a.localeCompare(b))
        .map((name) => ({ value: name, label: name })),
    [tokenUsageNamesData?.names],
  );

  const metricOptions = useMemo(() => {
    if (isMultiProject) {
      return WORKSPACE_STAT_METRIC_OPTIONS;
    }
    const scoreNames = data?.scores.map((s) => s.name) || [];
    return getAllMetricOptions(source, scoreNames);
  }, [isMultiProject, source, data?.scores]);

  const form = useForm<ProjectStatsCardWidgetFormData>({
    resolver: zodResolver(ProjectStatsCardWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      source,
      metric,
      projectIds: localProjectIds,
      usageMetric,
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

  const handleProjectsChange = (projectIds: string[]) => {
    const nextConfig = { ...config, projectIds };
    delete nextConfig.projectId;
    form.setValue("projectIds", projectIds);

    const becomesMultiProject = !hasRuntimeProjectId && projectIds.length >= 2;
    if (becomesMultiProject && !getWorkspaceStatMetric(metric)) {
      // Switching into multi-project with an incompatible metric: default to span token usage total.
      nextConfig.metric = METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;
      nextConfig.usageMetric = DEFAULT_WORKSPACE_USAGE_METRIC;
      nextConfig.traceFilters = [];
      nextConfig.spanFilters = [];
      form.setValue("metric", METRIC_NAME_TYPE.SPAN_TOKEN_USAGE);
      form.setValue("usageMetric", DEFAULT_WORKSPACE_USAGE_METRIC);
      form.setValue("traceFilters", []);
      form.setValue("spanFilters", []);
    } else if (!becomesMultiProject && getWorkspaceStatMetric(metric)) {
      // Leaving multi-project with a workspace-only metric selected: clear it so the user picks a valid one.
      nextConfig.metric = "";
      form.setValue("metric", "");
    }

    updatePreviewWidget({ config: nextConfig });
  };

  const handleMetricChange = (value: string) => {
    const nextConfig = { ...config, metric: value };
    // Picking the token-usage total needs a usage key; default it.
    if (
      isMultiProject &&
      getWorkspaceStatMetric(value)?.requiresUsageKey &&
      !config.usageMetric
    ) {
      nextConfig.usageMetric = DEFAULT_WORKSPACE_USAGE_METRIC;
      form.setValue("usageMetric", DEFAULT_WORKSPACE_USAGE_METRIC);
    }
    updatePreviewWidget({ config: nextConfig });
  };

  const handleUsageMetricChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        usageMetric: value,
      },
    });
  };

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="projectIds"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["projectIds"]);
            return (
              <FormItem>
                <FormLabel>Project</FormLabel>
                <FormControl>
                  <ProjectsSelectBox
                    className={cn("flex-1", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    multiselect
                    showSelectAll
                    selectAllLabel="All projects"
                    value={field.value || []}
                    onValueChange={(value) => {
                      field.onChange(value);
                      handleProjectsChange(value);
                    }}
                    disabled={hasRuntimeProjectId}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />

        {!isMultiProject && (
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
                      renderOption={renderSourceOption}
                      renderTrigger={renderSourceTrigger}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        )}

        <FormField
          control={form.control}
          name="metric"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["metric"]);
            const placeholder =
              !isMultiProject && isPending
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
                    disabled={!isMultiProject && isPending}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />

        {isMultiProject && requiresUsageKey && (
          <FormField
            control={form.control}
            name="usageMetric"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Usage metric</FormLabel>
                <FormControl>
                  <LoadableSelectBox
                    value={field.value || ""}
                    onChange={(value) => {
                      field.onChange(value);
                      handleUsageMetricChange(value);
                    }}
                    options={usageKeyOptions}
                    placeholder="Select a usage metric"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        )}

        <ProjectWidgetFiltersSection
          control={form.control}
          fieldName={useSpanFilters ? "spanFilters" : "traceFilters"}
          projectId={projectId}
          filterType={useSpanFilters ? "span" : "trace"}
          onFiltersChange={(filters) => {
            updatePreviewWidget({
              config: {
                ...config,
                ...(useSpanFilters
                  ? { spanFilters: filters }
                  : { traceFilters: filters }),
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
