import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  AddWidgetConfig,
  ChartMetricWidget,
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
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { useDashboardStore } from "@/store/DashboardStore";
import { ProjectDashboardConfig } from "@/types/dashboard";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection";
import {
  ProjectMetricsWidgetSchema,
  ProjectMetricsWidgetFormData,
} from "./schema";

type ProjectMetricsEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

const METRIC_OPTIONS = [
  {
    value: METRIC_NAME_TYPE.FEEDBACK_SCORES,
    label: "Trace Feedback Scores",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_COUNT,
    label: "Number of Traces",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_DURATION,
    label: "Trace Duration",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TOKEN_USAGE,
    label: "Token Usage",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.COST,
    label: "Estimated Cost",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.FAILED_GUARDRAILS,
    label: "Failed Guardrails",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_COUNT,
    label: "Number of Threads",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_DURATION,
    label: "Thread Duration",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
    label: "Thread Feedback Scores",
    filterType: "thread" as const,
  },
];

const CHART_TYPE_OPTIONS = [
  { value: "line", label: "Line Chart" },
  { value: "bar", label: "Bar Chart" },
];

const ProjectMetricsEditor = forwardRef<
  WidgetEditorHandle,
  ProjectMetricsEditorProps
>(({ title, subtitle, config, onChange }, ref) => {
  const widgetConfig = config as ChartMetricWidget["config"];
  const metricType = widgetConfig?.metricType || "";
  const chartType = widgetConfig?.chartType || "line";
  const localProjectId = widgetConfig?.projectId;

  const traceFilters = useMemo(
    () => widgetConfig?.traceFilters || [],
    [widgetConfig?.traceFilters],
  );
  const threadFilters = useMemo(
    () => widgetConfig?.threadFilters || [],
    [widgetConfig?.threadFilters],
  );

  const projectConfig = useDashboardStore(
    (state) => state.config as ProjectDashboardConfig | null,
  );
  const projectId = localProjectId || projectConfig?.projectId || "";

  const selectedMetric = METRIC_OPTIONS.find((m) => m.value === metricType);
  const isTraceMetric = selectedMetric?.filterType === "trace";
  const isThreadMetric = selectedMetric?.filterType === "thread";

  const form = useForm<ProjectMetricsWidgetFormData>({
    resolver: zodResolver(ProjectMetricsWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      title,
      subtitle: subtitle || "",
      metricType,
      chartType,
      projectId: localProjectId,
      traceFilters,
      threadFilters,
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
      const isValid = await form.trigger();
      if (isValid) {
        const values = form.getValues();
        onChange({
          title: values.title,
          subtitle: values.subtitle,
          config: {
            ...config,
            metricType: values.metricType,
            chartType: values.chartType,
            projectId: values.projectId,
            traceFilters: values.traceFilters,
            threadFilters: values.threadFilters,
          },
        });
      }
      return isValid;
    },
    isValid: form.formState.isValid,
  }));

  useEffect(() => {
    form.reset({
      title,
      subtitle: subtitle || "",
      metricType,
      chartType,
      projectId: localProjectId,
      traceFilters,
      threadFilters,
    });
  }, [
    title,
    subtitle,
    metricType,
    chartType,
    localProjectId,
    traceFilters,
    threadFilters,
    form,
  ]);

  const handleTitleChange = (value: string) => {
    onChange({ title: value });
  };

  const handleSubtitleChange = (value: string) => {
    onChange({ subtitle: value });
  };

  const handleMetricTypeChange = (value: string) => {
    onChange({
      config: {
        ...config,
        metricType: value,
      },
    });
  };

  const handleChartTypeChange = (value: string) => {
    onChange({
      config: {
        ...config,
        chartType: value as "line" | "bar",
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

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="title"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Widget title</FormLabel>
              <FormControl>
                <Input
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
          )}
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

        <FormField
          control={form.control}
          name="projectId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Project</FormLabel>
              <FormControl>
                <ProjectsSelectBox
                  value={field.value || ""}
                  onValueChange={(value) => {
                    field.onChange(value);
                    handleProjectChange(value);
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="metricType"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Metric</FormLabel>
              <FormControl>
                <SelectBox
                  value={field.value}
                  onChange={(value) => {
                    field.onChange(value);
                    handleMetricTypeChange(value);
                  }}
                  options={METRIC_OPTIONS}
                  placeholder="Select a metric"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="chartType"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Chart type</FormLabel>
              <FormControl>
                <SelectBox
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
          )}
        />
      </div>

      {metricType && (isTraceMetric || isThreadMetric) && (
        <ProjectWidgetFiltersSection
          control={form.control}
          fieldName={isTraceMetric ? "traceFilters" : "threadFilters"}
          projectId={projectId}
          filterType={isTraceMetric ? "trace" : "thread"}
          onFiltersChange={(filters) => {
            onChange({
              config: {
                ...config,
                ...(isTraceMetric
                  ? { traceFilters: filters }
                  : { threadFilters: filters }),
              },
            });
          }}
          className="mt-4"
        />
      )}
    </Form>
  );
});

ProjectMetricsEditor.displayName = "ProjectMetricsEditor";

export default ProjectMetricsEditor;
