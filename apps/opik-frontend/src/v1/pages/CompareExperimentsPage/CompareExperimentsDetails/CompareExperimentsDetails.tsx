import React, { useEffect, useMemo } from "react";
import sortBy from "lodash/sortBy";
import isNumber from "lodash/isNumber";
import { CircleCheck } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { Experiment } from "@/types/datasets";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DateTag from "@/shared/DateTag/DateTag";
import NavigationTag from "@/shared/NavigationTag";
import ExperimentTag from "@/shared/ExperimentTag/ExperimentTag";
import FeedbackScoresList from "@/v1/pages-shared/FeedbackScoresList/FeedbackScoresList";
import { formatPassRate } from "@/shared/DataTableCells/PassRateCell";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import {
  FeedbackScoreDisplay,
  SCORE_TYPE_FEEDBACK,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";
import { getScoreDisplayName } from "@/lib/feedback-scores";
import { generateExperimentIdFilter } from "@/lib/filters";
import { isEvalSuiteExperiment } from "@/lib/experiments";
import ExperimentTagsList from "@/v1/pages/CompareExperimentsPage/ExperimentTagsList";

type CompareExperimentsDetailsProps = {
  experimentsIds: string[];
  experiments: Experiment[];
};

const CompareExperimentsDetails: React.FunctionComponent<
  CompareExperimentsDetailsProps
> = ({ experiments, experimentsIds }) => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const isCompare = experimentsIds.length > 1;

  const experiment = experiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  useEffect(() => {
    title && setBreadcrumbParam("compare", "compare", title);
    return () => setBreadcrumbParam("compare", "compare", "");
  }, [title, setBreadcrumbParam]);

  const experimentTracesSearch = useMemo(
    () => ({
      traces_filters: generateExperimentIdFilter(experimentsIds[0]),
    }),
    [experimentsIds],
  );

  const experimentScores: FeedbackScoreDisplay[] = useMemo(() => {
    if (isCompare || !experiment) return [];
    return sortBy(
      [
        ...(experiment.feedback_scores ?? []).map((score) => ({
          ...score,
          colorKey: score.name,
          name: getScoreDisplayName(score.name, SCORE_TYPE_FEEDBACK),
        })),
        ...(experiment.experiment_scores ?? []).map((score) => ({
          ...score,
          colorKey: score.name,
          name: getScoreDisplayName(score.name, SCORE_TYPE_EXPERIMENT),
        })),
      ],
      "name",
    );
  }, [isCompare, experiment]);

  const renderSubSection = () => {
    if (isCompare) {
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
    }

    return <FeedbackScoresList scores={experimentScores} />;
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
            resource={RESOURCE_TYPE.experiment}
          />
        )}
        <NavigationTag
          id={experiment?.dataset_id}
          name={experiment?.dataset_name && `Go to ${experiment.dataset_name}`}
          resource={RESOURCE_TYPE.dataset}
        />
        {experiment?.prompt_versions &&
          experiment.prompt_versions.length > 0 && (
            <NavigationTag
              id={experiment.prompt_versions[0].prompt_id}
              name={`Go to ${experiment.prompt_versions[0].prompt_name}`}
              resource={RESOURCE_TYPE.prompt}
            />
          )}
        {!isCompare && experiment?.project_id && (
          <NavigationTag
            resource={RESOURCE_TYPE.traces}
            id={experiment.project_id}
            name="Go to traces"
            search={experimentTracesSearch}
            tooltipContent="View all traces for this experiment"
          />
        )}
        {!isCompare &&
          isEvalSuiteExperiment(experiment) &&
          isNumber(experiment.pass_rate) && (
            <TooltipWrapper
              content={formatPassRate(
                experiment.pass_rate,
                experiment.passed_count,
                experiment.total_count,
              )}
            >
              <Tag
                size="md"
                variant="transparent"
                className="flex shrink-0 items-center gap-1"
              >
                <CircleCheck
                  className={`size-3 shrink-0 ${
                    experiment.pass_rate === 1
                      ? "text-[var(--color-green)]"
                      : "text-[var(--color-red)]"
                  }`}
                />
                <div className="comet-body-s-accented truncate text-muted-slate">
                  {Math.round(experiment.pass_rate * 100)}% pass rate
                </div>
              </Tag>
            </TooltipWrapper>
          )}
      </div>
      {!isCompare && experiment && (
        <ExperimentTagsList
          tags={experiment?.tags ?? []}
          experimentId={experiment.id}
          experiment={experiment}
        />
      )}
      {renderSubSection()}
    </div>
  );
};

export default CompareExperimentsDetails;
