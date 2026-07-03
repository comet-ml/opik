import React, { useEffect, useMemo } from "react";
import { Database, History } from "lucide-react";

import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import NavigationTag from "@/shared/NavigationTag";
import { usePermissions } from "@/contexts/PermissionsContext";
import { formatDate } from "@/lib/date";
import { LOGS_SOURCE } from "@/types/traces";
import { generateExperimentIdsFilter } from "@/lib/filters";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "@/v2/pages-shared/optimizations/OptimizationConfigPill";
import TrialStatusPill from "@/v2/pages-shared/optimizations/TrialStatusPill";
import { type TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type TrialDetailsProps = {
  optimization?: Optimization;
  experiments: Experiment[];
  baselineExperimentId?: string;
  baselineScore?: number;
  trialNumber?: number;
};

const TrialDetails: React.FC<TrialDetailsProps> = ({
  optimization,
  experiments,
  baselineExperimentId,
  baselineScore,
  trialNumber,
}) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const experiment = experiments[0];

  const title = trialNumber ? `Trial #${trialNumber}` : "Trial";

  const trialStatus: TrialStatus | undefined = useMemo(() => {
    if (!experiment) return undefined;
    if (experiment.id === baselineExperimentId) return "baseline";
    const objectiveName = optimization?.objective_name;
    if (!objectiveName) return undefined;
    const score = getFeedbackScoreValue(
      experiment.feedback_scores ?? [],
      objectiveName,
    );
    if (score == null || baselineScore == null) return undefined;
    return score >= baselineScore ? "passed" : "pruned";
  }, [
    experiment,
    baselineExperimentId,
    baselineScore,
    optimization?.objective_name,
  ]);

  useEffect(() => {
    if (title) {
      setBreadcrumbParam("trial", "trials", title);
    }
    return () => setBreadcrumbParam("trial", "trials", "");
  }, [title, setBreadcrumbParam]);

  return (
    <div className="py-4">
      <div className="mb-2 flex min-h-8 items-center gap-1.5">
        <h1 className="comet-body-accented truncate break-words">{title}</h1>
        {trialStatus && <TrialStatusPill status={trialStatus} />}
      </div>
      <div className="mb-1 flex items-center gap-2 overflow-x-auto">
        {experiment?.created_at && (
          <OptimizationConfigPill
            className="shrink-0"
            icon={<History className={CONFIG_PILL_ICON_CLASS} />}
          >
            {formatDate(experiment.created_at)}
          </OptimizationConfigPill>
        )}
        {canViewDatasets &&
          experiment?.dataset_id &&
          experiment?.dataset_name && (
            <NavigationTag
              id={experiment.dataset_id}
              name={experiment.dataset_name}
              resource={RESOURCE_TYPE.dataset}
              icon={Database}
              className="rounded-md"
            />
          )}
        {experiment?.project_id && (
          <TraceLogsSidebarButton
            projectId={experiment.project_id}
            logsSource={LOGS_SOURCE.optimization}
            sourceFilters={generateExperimentIdsFilter([experiment.id])}
            title="Optimization logs"
          />
        )}
      </div>
    </div>
  );
};
export default TrialDetails;
