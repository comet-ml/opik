import React, { useEffect, useMemo } from "react";
import sortBy from "lodash/sortBy";
import isNumber from "lodash/isNumber";
import { CircleCheck, GitCommitVertical } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import BackButton from "@/shared/BackButton/BackButton";
import CompareExperimentsButton from "@/v2/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton";
import { Experiment } from "@/types/datasets";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DateTag from "@/shared/DateTag/DateTag";
import NavigationTag from "@/shared/NavigationTag";
import ExperimentTag from "@/shared/ExperimentTag/ExperimentTag";
import FeedbackScoresList from "@/v2/pages-shared/FeedbackScoresList/FeedbackScoresList";
import { formatPassRate } from "@/shared/DataTableCells/PassRateCell";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import {
  FeedbackScoreDisplay,
  SCORE_TYPE_FEEDBACK,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";
import { getScoreDisplayName } from "@/lib/feedback-scores";
import { generateExperimentIdsFilter } from "@/lib/filters";
import { isTestSuiteExperiment } from "@/lib/experiments";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";
import ExperimentTagsList from "@/v2/pages/CompareExperimentsPage/ExperimentTagsList";

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
    title && setBreadcrumbParam("compare", "Compare", title);
    return () => setBreadcrumbParam("compare", "Compare", "");
  }, [title, setBreadcrumbParam]);

  const experimentSourceFilters = useMemo(
    () => generateExperimentIdsFilter(experimentsIds),
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
        <div className="mt-1 flex items-center gap-2">
          <span className="text-nowrap">Baseline of</span>
          <ExperimentTag experimentName={experiment?.name} />
          <span className="text-nowrap">compared against</span>
          {tag}
        </div>
      );
    }

    return <FeedbackScoresList className="mt-1" scores={experimentScores} />;
  };

  return (
    <div className="py-4">
      <div className="mb-3 flex min-h-8 items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <BackButton
            to="/$workspaceName/projects/$projectId/experiments"
            tooltip="Back to experiments"
          />
          <h1 className="comet-body-accented truncate break-words">{title}</h1>
        </div>
        <CompareExperimentsButton />
      </div>
      <div className="mb-1 flex gap-1.5 overflow-x-auto">
        {!isCompare && (
          <DateTag
            date={experiment?.created_at}
            resource={RESOURCE_TYPE.experiment}
          />
        )}
        {experiment?.dataset_id && (
          <NavigationTag
            id={experiment.dataset_id}
            name={experiment.dataset_name}
            tooltipContent={false}
            resource={
              isTestSuiteExperiment(experiment)
                ? RESOURCE_TYPE.testSuite
                : RESOURCE_TYPE.dataset
            }
            prefix={
              isTestSuiteExperiment(experiment) ? "Test suite" : "Dataset"
            }
            suffix={
              experiment.dataset_version_summary?.version_name ? (
                <span className="flex items-center gap-0 pt-px text-xs text-muted-slate">
                  <GitCommitVertical className="size-[10px]" />
                  {experiment.dataset_version_summary.version_name}
                </span>
              ) : undefined
            }
          />
        )}
        {experiment?.prompt_versions &&
          experiment.prompt_versions.length > 0 && (
            <NavigationTag
              id={experiment.prompt_versions[0].prompt_id}
              name={experiment.prompt_versions[0].prompt_name}
              resource={RESOURCE_TYPE.prompt}
              prefix="Prompt"
            />
          )}
        {experiment?.project_id && (
          <TraceLogsSidebarButton
            projectId={experiment.project_id}
            sourceFilters={experimentSourceFilters}
            title="Experiment logs"
          />
        )}
        {!isCompare &&
          isTestSuiteExperiment(experiment) &&
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
