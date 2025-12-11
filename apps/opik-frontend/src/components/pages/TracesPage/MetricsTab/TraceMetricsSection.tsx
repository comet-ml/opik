import React, { useMemo } from "react";
import { JsonParam, useQueryParam } from "use-query-params";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_SPAN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_ID_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { BaseTraceData } from "@/types/traces";
import {
  METRIC_NAME_TYPE,
  INTERVAL_TYPE,
} from "@/api/projects/useProjectMetric";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import { generateVisibilityFilters } from "@/lib/filters";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { GuardrailResult } from "@/types/guardrails";
import { Filter } from "@/types/filters";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import MetricContainerChart from "./MetricChart/MetricChartContainer";
import { CHART_TYPE } from "@/constants/chart";
import {
  DURATION_LABELS_MAP,
  INTERVAL_DESCRIPTIONS,
  renderDurationTooltipValue,
  durationYTickFormatter,
  tokenYTickFormatter,
} from "./utils";

const TRACE_FILTER_COLUMNS: ColumnData<BaseTraceData>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "input",
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "error_info",
    label: "Errors",
    type: COLUMN_TYPE.errors,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_SPAN_FEEDBACK_SCORES_ID,
    label: "Span feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

interface TraceMetricsSectionProps {
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  hasTraces: boolean;
}

const TraceMetricsSection: React.FC<TraceMetricsSectionProps> = ({
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  hasTraces,
}) => {
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const [traceFilters = [], setTraceFilters] = useQueryParam(
    "traces_metrics_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const processedTracesFilters = useMemo(() => {
    return [...traceFilters, ...generateVisibilityFilters()];
  }, [traceFilters]);

  const traceFiltersConfig = useMemo(
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
        [COLUMN_SPAN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FC<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: TRACE_DATA_TYPE.spans,
            placeholder: "Select span score",
          },
        },
        [COLUMN_GUARDRAILS_ID]: {
          keyComponentProps: {
            options: [
              { value: GuardrailResult.FAILED, label: "Failed" },
              { value: GuardrailResult.PASSED, label: "Passed" },
            ],
            placeholder: "Status",
          },
        },
      },
    }),
    [projectId],
  );

  const traceFilterColumns = useMemo(() => {
    const baseColumns = [...TRACE_FILTER_COLUMNS];

    if (isGuardrailsEnabled) {
      baseColumns.push({
        id: COLUMN_GUARDRAILS_ID,
        label: "Guardrails",
        type: COLUMN_TYPE.category,
      });
    }

    return baseColumns;
  }, [isGuardrailsEnabled]);

  if (!hasTraces) {
    return null;
  }

  return (
    <div className="pt-6">
      <div className="sticky top-0 z-10 flex items-center justify-between bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-s truncate break-words">Trace metrics</h2>
        <FiltersButton
          columns={traceFilterColumns}
          filters={traceFilters}
          onChange={setTraceFilters}
          config={traceFiltersConfig}
        />
      </div>
      <div
        className="grid grid-cols-1 gap-4 md:grid-cols-2"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        <div>
          <MetricContainerChart
            chartId="feedback_scores_chart"
            key="feedback_scores_chart"
            name="Trace feedback scores"
            description={INTERVAL_DESCRIPTIONS.AVERAGES[interval]}
            metricName={METRIC_NAME_TYPE.FEEDBACK_SCORES}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            chartType={CHART_TYPE.line}
            traceFilters={processedTracesFilters}
          />
        </div>
        <div>
          <MetricContainerChart
            chartId="number_of_traces_chart"
            key="number_of_traces_chart"
            name="Number of traces"
            description={INTERVAL_DESCRIPTIONS.TOTALS[interval]}
            metricName={METRIC_NAME_TYPE.TRACE_COUNT}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            customYTickFormatter={tokenYTickFormatter}
            chartType={CHART_TYPE.line}
            traceFilters={processedTracesFilters}
          />
        </div>
        <div className="md:col-span-2">
          <MetricContainerChart
            chartId="duration_chart"
            key="duration_chart"
            name="Trace duration"
            description={INTERVAL_DESCRIPTIONS.QUANTILES[interval]}
            metricName={METRIC_NAME_TYPE.TRACE_DURATION}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            renderValue={renderDurationTooltipValue}
            labelsMap={DURATION_LABELS_MAP}
            customYTickFormatter={durationYTickFormatter}
            chartType={CHART_TYPE.line}
            traceFilters={processedTracesFilters}
          />
        </div>
        {isGuardrailsEnabled && (
          <div className="md:col-span-2">
            <MetricContainerChart
              chartId="failed_guardrails_chart"
              key="failed_guardrails_chart"
              name="Failed guardrails"
              description={INTERVAL_DESCRIPTIONS.TOTALS[interval]}
              metricName={METRIC_NAME_TYPE.FAILED_GUARDRAILS}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType={CHART_TYPE.bar}
              traceFilters={processedTracesFilters}
            />
          </div>
        )}
      </div>
    </div>
  );
};

export default TraceMetricsSection;
