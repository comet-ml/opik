import React, { useEffect, useMemo } from "react";
import { FlaskConical } from "lucide-react";

import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import DateTag from "@/components/shared/DateTag/DateTag";
import { Tag } from "@/components/ui/tag";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { formatNumericData } from "@/lib/utils";

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

  const isCompare = experimentsIds.length > 1;

  const experiment = experiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  const score = useMemo(
    () =>
      !isCompare &&
      experiment &&
      optimization?.objective_name &&
      getFeedbackScore(
        experiment.feedback_scores ?? [],
        optimization.objective_name,
      ),
    [experiment, isCompare, optimization?.objective_name],
  );

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
        <Tag size="lg" variant="gray" className="flex items-center gap-2">
          <FlaskConical className="size-4 shrink-0" />
          <div className="truncate">{experiments[1]?.name}</div>
        </Tag>
      ) : (
        <Tag size="lg" variant="gray">
          {`${experimentsIds.length - 1} experiments`}
        </Tag>
      );

    return (
      <div className="flex h-11 items-center gap-2">
        <span className="text-nowrap">Baseline of</span>
        <Tag size="lg" variant="gray" className="flex items-center gap-2">
          <FlaskConical className="size-4 shrink-0" />
          <div className="truncate">{experiment?.name}</div>
        </Tag>
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
      <div className="mb-1 flex gap-4 overflow-x-auto">
        {!isCompare && <DateTag date={experiment?.created_at} />}
        <ResourceLink
          id={experiment?.dataset_id}
          name={experiment?.dataset_name}
          resource={RESOURCE_TYPE.dataset}
          asTag
        />
        {score && (
          <FeedbackScoreTag
            key={score.name + score.value}
            label={score.name}
            value={formatNumericData(score.value)}
          />
        )}
      </div>
      {renderSubSection()}
    </div>
  );
};
export default CompareTrialsDetails;
