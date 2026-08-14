import React, { useMemo } from "react";

import { Experiment } from "@/types/datasets";
import { generateExperimentIdsFilter } from "@/lib/filters";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import emptyLogsLightUrl from "/images/empty-logs-light.svg";
import emptyLogsDarkUrl from "/images/empty-logs-dark.svg";
import TraceLogsView, {
  DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  TraceLogsViewConfig,
} from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

// Per design, the tab's toolbar carries the columns selector and nothing else — no row-height, date
// range or refresh. Dropping the date control also drops date filtering entirely, so the tab always
// spans the experiment's whole life.
const EXPERIMENT_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  ...DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  showTableControls: false,
};

type ExperimentLogsTabProps = {
  experimentsIds: string[];
  experiments: Experiment[];
};

/**
 * Traces produced by the open experiment (or, when comparing, by all compared experiments).
 *
 * The experiment scope is applied as a locked scope rather than seeded into the filter bar: a tab
 * named "Logs" inside an experiment should always mean that experiment's logs, and there is no
 * "open" event a seeded filter could hang off. No scope indicator is rendered — the page around the
 * tab already says which experiment this is (OPIK-6739).
 */
const ExperimentLogsTab: React.FunctionComponent<ExperimentLogsTabProps> = ({
  experimentsIds,
  experiments,
}) => {
  const scopeFilters = useMemo(
    () => generateExperimentIdsFilter(experimentsIds),
    [experimentsIds],
  );

  // Compared experiments can only be compared within a dataset, so they share a project; the first
  // loaded experiment is what tells us which one.
  const projectId = experiments.find((e) => e.project_id)?.project_id;

  // Nothing to render before the experiment loads. Once it has, a missing project means the run
  // never produced traces — say so rather than leaving the tab blank.
  if (!projectId) {
    if (experiments.length === 0) return null;

    return (
      <div className="py-8">
        <DataTableEmptyContent
          title="There are no traces yet"
          description="Traces will appear here once this experiment records them."
          lightImageUrl={emptyLogsLightUrl}
          darkImageUrl={emptyLogsDarkUrl}
        />
      </div>
    );
  }

  return (
    <TraceLogsView
      projectId={projectId}
      scopeFilters={scopeFilters}
      viewConfig={EXPERIMENT_LOGS_VIEW_CONFIG}
      layout="page"
    />
  );
};

export default ExperimentLogsTab;
