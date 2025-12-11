import React, { useMemo } from "react";
import { JsonParam, useQueryParam } from "use-query-params";

import {
  COLUMN_FEEDBACK_SCORES_ID,
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
  INTERVAL_DESCRIPTIONS,
  renderCostTooltipValue,
  costYTickFormatter,
  tokenYTickFormatter,
} from "./utils";

const PROJECT_METRICS_FILTER_COLUMNS: ColumnData<BaseTraceData>[] = [
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
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

interface ProjectMetricsSectionProps {
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  hasTraces: boolean;
}

const ProjectMetricsSection: React.FC<ProjectMetricsSectionProps> = ({
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  hasTraces,
}) => {
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const [projectMetricsFilters = [], setProjectMetricsFilters] = useQueryParam(
    "project_metrics_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const processedProjectMetricsFilters = useMemo(() => {
    return [...projectMetricsFilters, ...generateVisibilityFilters()];
  }, [projectMetricsFilters]);

  const projectMetricsFiltersConfig = useMemo(
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

  const projectMetricsFilterColumns = useMemo(() => {
    const baseColumns = [...PROJECT_METRICS_FILTER_COLUMNS];

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
        <h2 className="comet-title-s truncate break-words">Project metrics</h2>
        <FiltersButton
          columns={projectMetricsFilterColumns}
          filters={projectMetricsFilters}
          onChange={setProjectMetricsFilters}
          config={projectMetricsFiltersConfig}
        />
      </div>
      <div
        className="grid grid-cols-1 gap-4 md:grid-cols-2"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        <div>
          <MetricContainerChart
            chartId="token_usage_chart"
            key="token_usage_chart"
            name="Token usage"
            description={INTERVAL_DESCRIPTIONS.TOTALS[interval]}
            metricName={METRIC_NAME_TYPE.TOKEN_USAGE}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            customYTickFormatter={tokenYTickFormatter}
            chartType={CHART_TYPE.line}
            traceFilters={processedProjectMetricsFilters}
          />
        </div>
        <div>
          <MetricContainerChart
            chartId="estimated_cost_chart"
            key="estimated_cost_chart"
            name="Estimated cost"
            description={INTERVAL_DESCRIPTIONS.COST[interval]}
            metricName={METRIC_NAME_TYPE.COST}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            renderValue={renderCostTooltipValue}
            customYTickFormatter={costYTickFormatter}
            chartType={CHART_TYPE.line}
            traceFilters={processedProjectMetricsFilters}
          />
        </div>
      </div>
    </div>
  );
};

export default ProjectMetricsSection;
