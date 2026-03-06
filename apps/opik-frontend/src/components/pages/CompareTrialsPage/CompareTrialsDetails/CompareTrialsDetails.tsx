import React, { useEffect, useMemo } from "react";

import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import DateTag from "@/components/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import NavigationTag from "@/components/shared/NavigationTag";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Tag, TagProps } from "@/components/ui/tag";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";

type TrialStatus = "baseline" | "passed" | "pruned";

const STATUS_VARIANT_MAP: Record<TrialStatus, TagProps["variant"]> = {
  baseline: "gray",
  passed: "blue",
  pruned: "pink",
};

type CompareTrialsDetailsProps = {
  optimization?: Optimization;
  experiments: Experiment[];
  baselineExperimentId?: string;
  baselineScore?: number;
  trialNumber?: number;
};

const CompareTrialsDetails: React.FC<CompareTrialsDetailsProps> = ({
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

  const title = trialNumber ? `Trial #${trialNumber}` : experiment?.name;

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
      setBreadcrumbParam("trialsCompare", "trialsCompare", title);
    }

    if (optimization?.name && optimization?.id) {
      setBreadcrumbParam("optimizationId", optimization.id, optimization.name);
    }
    return () => setBreadcrumbParam("trialsCompare", "trialsCompare", "");
  }, [title, setBreadcrumbParam, optimization?.name, optimization?.id]);

  return (
    <div className="pb-4 pt-6">
      <div className="mb-4 flex min-h-8 items-center gap-3">
        <h1 className="comet-title-l truncate break-words">{title}</h1>
        {trialStatus && (
          <Tag
            variant={STATUS_VARIANT_MAP[trialStatus]}
            size="md"
            className="shrink-0 capitalize"
          >
            {trialStatus}
          </Tag>
        )}
      </div>
      <div className="mb-1 flex gap-2 overflow-x-auto">
        <DateTag date={experiment?.created_at} resource={RESOURCE_TYPE.trial} />
        {canViewDatasets && (
          <NavigationTag
            id={experiment?.dataset_id}
            name={
              experiment?.dataset_name && `Go to ${experiment.dataset_name}`
            }
            resource={RESOURCE_TYPE.dataset}
          />
        )}
      </div>
    </div>
  );
};
export default CompareTrialsDetails;
