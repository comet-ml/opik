import React, { useMemo, useCallback } from "react";
import uniqid from "uniqid";
import { Plus } from "lucide-react";

import { AddWidgetConfig, ChartMetricWidget } from "@/types/dashboard";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { Filter } from "@/types/filters";
import { createFilter } from "@/lib/filters";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { BaseTraceData, Thread } from "@/types/traces";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import { useDashboardStore } from "@/store/DashboardStore";
import { ProjectDashboardConfig } from "@/types/dashboard";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import { DateRangeValue } from "@/components/shared/DateRangeSelect";

type ProjectMetricsEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
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

const TRACE_FILTER_COLUMNS: ColumnData<BaseTraceData>[] = [
  { id: COLUMN_ID_ID, label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "start_time", label: "Start time", type: COLUMN_TYPE.time },
  { id: "end_time", label: "End time", type: COLUMN_TYPE.time },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "thread_id", label: "Thread ID", type: COLUMN_TYPE.string },
  { id: "error_info", label: "Errors", type: COLUMN_TYPE.errors },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.string,
  },
];

const THREAD_FILTER_COLUMNS: ColumnData<Thread>[] = [
  { id: COLUMN_ID_ID, label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "start_time", label: "Start time", type: COLUMN_TYPE.time },
  { id: "end_time", label: "End time", type: COLUMN_TYPE.time },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.string,
  },
];

const ProjectMetricsEditor: React.FC<ProjectMetricsEditorProps> = ({
  title,
  subtitle,
  config,
  onChange,
}) => {
  const widgetConfig = config as ChartMetricWidget["config"];
  const metricType = widgetConfig?.metricType || "";
  const chartType = widgetConfig?.chartType || "line";
  const useGlobalDateRange = widgetConfig?.useGlobalDateRange ?? true;
  const localProjectId = widgetConfig?.projectId;
  const localDateRange = widgetConfig?.dateRange;

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

  const currentFilters = isTraceMetric ? traceFilters : threadFilters;
  const currentFilterColumns = isTraceMetric
    ? TRACE_FILTER_COLUMNS
    : THREAD_FILTER_COLUMNS;

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: true,
          },
        },
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: false,
          },
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should begin with "input", or "output" and follow this format: "input.[PATH]" For example: "input.message" `;
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FC<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "Select score",
          },
        },
      },
    }),
    [projectId],
  );

  const handleTitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ title: e.target.value });
  };

  const handleSubtitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ subtitle: e.target.value });
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

  const handleUseGlobalDateRangeChange = (checked: boolean) => {
    onChange({
      config: {
        ...config,
        useGlobalDateRange: checked,
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

  const handleDateRangeChange = (dateRange: DateRangeValue) => {
    onChange({
      config: {
        ...config,
        dateRange,
      },
    });
  };

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      const currentFilters = isTraceMetric ? traceFilters : threadFilters;
      const newFilters =
        typeof filtersOrUpdater === "function"
          ? filtersOrUpdater(currentFilters)
          : filtersOrUpdater;

      onChange({
        config: {
          ...config,
          ...(isTraceMetric
            ? { traceFilters: newFilters }
            : { threadFilters: newFilters }),
        },
      });
    },
    [isTraceMetric, traceFilters, threadFilters, config, onChange],
  );

  const handleAddFilter = useCallback(() => {
    const newFilter = {
      ...createFilter(),
      id: uniqid(),
    };
    setFilters((prev) => [...prev, newFilter]);
  }, [setFilters]);

  return (
    <div className="flex h-full flex-col gap-4 overflow-auto p-4">
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="widget-title">Widget title</Label>
          <Input
            id="widget-title"
            placeholder="Enter widget title"
            value={title}
            onChange={handleTitleChange}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="widget-subtitle">Widget subtitle (optional)</Label>
          <Input
            id="widget-subtitle"
            placeholder="Enter widget subtitle"
            value={subtitle || ""}
            onChange={handleSubtitleChange}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="project">Project</Label>
          <ProjectsSelectBox
            value={localProjectId || ""}
            onValueChange={handleProjectChange}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="metric-type">Metric</Label>
          <SelectBox
            id="metric-type"
            value={metricType}
            onChange={handleMetricTypeChange}
            options={METRIC_OPTIONS}
            placeholder="Select a metric"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="chart-type">Chart type</Label>
          <SelectBox
            id="chart-type"
            value={chartType}
            onChange={handleChartTypeChange}
            options={CHART_TYPE_OPTIONS}
            placeholder="Select chart type"
          />
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label htmlFor="use-global-date-range">
                Use dashboard date range
              </Label>
              <p className="text-xs text-muted-foreground">
                Use global dashboard time settings
              </p>
            </div>
            <Switch
              id="use-global-date-range"
              checked={useGlobalDateRange}
              onCheckedChange={handleUseGlobalDateRangeChange}
            />
          </div>
        </div>

        {!useGlobalDateRange && (
          <div className="space-y-2">
            <Label htmlFor="date-range">Date range</Label>
            <MetricDateRangeSelect
              value={localDateRange || { from: new Date(), to: new Date() }}
              onChangeValue={handleDateRangeChange}
            />
          </div>
        )}
      </div>

      {metricType && (isTraceMetric || isThreadMetric) && (
        <Accordion type="single" collapsible className="w-full border-t">
          <AccordionItem value="filters" className="border-none">
            <AccordionTrigger className="py-3 hover:no-underline">
              <Label className="text-sm font-medium">
                Filters{" "}
                {currentFilters.length > 0 && `(${currentFilters.length})`}
              </Label>
            </AccordionTrigger>
            <AccordionContent className="space-y-3 pb-3">
              {currentFilters.length > 0 && (
                <FiltersContent<BaseTraceData | Thread>
                  filters={currentFilters}
                  setFilters={setFilters}
                  columns={
                    currentFilterColumns as ColumnData<BaseTraceData | Thread>[]
                  }
                  config={filtersConfig}
                  className="py-0"
                />
              )}

              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleAddFilter}
                className="w-fit"
              >
                <Plus className="mr-1 size-3.5" />
                Add filter
              </Button>
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      )}
    </div>
  );
};

export default ProjectMetricsEditor;
