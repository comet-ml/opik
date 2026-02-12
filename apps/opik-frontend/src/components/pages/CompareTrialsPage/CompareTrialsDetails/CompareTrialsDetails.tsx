import React, { useEffect, useMemo } from "react";

import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import DateTag from "@/components/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { generateDistinctColorMap } from "@/components/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";
import NavigationTag from "@/components/shared/NavigationTag";
import ExperimentTag from "@/components/shared/ExperimentTag/ExperimentTag";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";

type CompareTrialsDetailsProps = {
  optimization?: Optimization;
  experimentsIds: string[];
  experiments: Experiment[];
};

const CompareTrialsDetails: React.FC<CompareTrialsDetailsProps> = ({
  optimization,
  experiments,
  experimentsIds,
}) => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const { getColor } = useWorkspaceColorMap();

  const isCompare = experimentsIds.length > 1;

  const experiment = experiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  const scores = useMemo(() => {
    if (isCompare || !experiment?.feedback_scores) return [];

    const objectiveName = optimization?.objective_name;

    // Sort scores: main objective first, then alphabetically
    return [...experiment.feedback_scores].sort((a, b) => {
      if (a.name === objectiveName) return -1;
      if (b.name === objectiveName) return 1;
      return a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
    });
  }, [experiment, isCompare, optimization?.objective_name]);

  const colorMap = useMemo(() => {
    if (!optimization?.objective_name || scores.length === 0) return {};

    const secondaryScoreNames = scores
      .filter((score) => score.name !== optimization.objective_name)
      .map((score) => score.name);

    return generateDistinctColorMap(
      optimization.objective_name,
      secondaryScoreNames,
    );
  }, [optimization?.objective_name, scores]);

  useEffect(() => {
    if (title) {
      setBreadcrumbParam("trialsCompare", "trialsCompare", title);
    }

    if (optimization?.name && optimization?.id) {
      setBreadcrumbParam("optimizationId", optimization.id, optimization.name);
    }
    return () => setBreadcrumbParam("trialsCompare", "trialsCompare", "");
  }, [title, setBreadcrumbParam, optimization?.name, optimization?.id]);

  const renderSubSection = () => {
    if (!isCompare) return null;

    const tag =
      experimentsIds.length === 2 ? (
        <ExperimentTag experimentName={experiments[1]?.name} />
      ) : (
        <ExperimentTag count={experimentsIds.length - 1} />
      );

    return (
      <div className="flex h-11 items-center gap-2">
        <span className="text-nowrap">Baseline of</span>
        <ExperimentTag experimentName={experiment?.name} />
        <span className="text-nowrap">compared against</span>
        {tag}
      </div>
    );
  };

  return (
    <div className="pb-4 pt-6">
      <div className="mb-4 flex min-h-8 items-center justify-between">
        <h1 className="comet-title-l truncate break-words">{title}</h1>
      </div>
      <div className="mb-1 flex gap-2 overflow-x-auto">
        {!isCompare && (
          <DateTag
            date={experiment?.created_at}
            resource={RESOURCE_TYPE.trial}
          />
        )}
        <NavigationTag
          id={experiment?.dataset_id}
          name={experiment?.dataset_name}
          resource={RESOURCE_TYPE.dataset}
        />
        {scores.map((score) => (
          <FeedbackScoreTag
            key={score.name + score.value}
            label={score.name}
            value={score.value}
            color={getColor(score.name, colorMap)}
          />
        ))}
      </div>
      {renderSubSection()}
    </div>
  );
};
export default CompareTrialsDetails;
