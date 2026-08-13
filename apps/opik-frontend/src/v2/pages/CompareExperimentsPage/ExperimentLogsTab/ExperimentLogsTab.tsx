import React, { useMemo } from "react";

import { Experiment } from "@/types/datasets";
import { generateExperimentIdsFilter } from "@/lib/filters";
import TraceLogsView from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

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

  if (!projectId) return null;

  return (
    <TraceLogsView
      projectId={projectId}
      scopeFilters={scopeFilters}
      layout="page"
    />
  );
};

export default ExperimentLogsTab;
