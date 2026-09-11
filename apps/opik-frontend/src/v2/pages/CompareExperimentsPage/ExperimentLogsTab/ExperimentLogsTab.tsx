import React, { useMemo } from "react";
import isString from "lodash/isString";
import uniq from "lodash/uniq";

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
//
// Its own storage namespace, so the columns, sort, page size and pinned chips a user sets here
// don't overwrite the ones they set in the playground or trial overlays, which take the default
// config. Without this every host shares one arrangement.
const EXPERIMENT_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  ...DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  storageNamespace: "experiment-",
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
    // JsonParam yields whatever the URL parsed to, so a malformed link could otherwise put a number
    // or object through join(",") and emit a filter like "[object Object]".
    () => generateExperimentIdsFilter(experimentsIds.filter(isString)),
    [experimentsIds],
  );

  // Traces are queried per project, so comparing experiments that live in different ones cannot be
  // served by a single request. That happens: an experiment's project resolves from its own
  // project_name, independently of the dataset, and the dataset lookup itself is project-agnostic.
  const projectIds = useMemo(
    () => uniq(experiments.map((e) => e.project_id).filter(isString)),
    [experiments],
  );
  const projectId = projectIds[0];

  // Nothing to render before the experiment loads.
  if (experiments.length === 0) return null;

  if (projectIds.length > 1) {
    return (
      <div className="py-8">
        <DataTableEmptyContent
          title="These experiments live in different projects"
          description="Logs are read per project, so they can't be listed together. Open an experiment on its own to see its traces."
          lightImageUrl={emptyLogsLightUrl}
          darkImageUrl={emptyLogsDarkUrl}
        />
      </div>
    );
  }

  // A loaded experiment with no project never produced traces — say so rather than leaving the tab
  // blank.
  if (!projectId) {
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
