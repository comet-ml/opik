import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";

import DateTag from "@/components/shared/DateTag/DateTag";
import NavigationTag from "@/components/shared/NavigationTag";
import FeedbackScoresList from "@/components/pages-shared/FeedbackScoresList/FeedbackScoresList";
import ExperimentTagsList from "@/components/pages/CompareExperimentsPage/ExperimentTagsList";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Experiment } from "@/types/datasets";
import {
  FeedbackScoreDisplay,
  SCORE_TYPE_FEEDBACK,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";
import { getScoreDisplayName } from "@/lib/feedback-scores";
import { generateExperimentIdFilter } from "@/lib/filters";

type EvaluationSuiteExperimentDetailsProps = {
  experiment: Experiment;
  suiteId: string;
};

const EvaluationSuiteExperimentDetails: React.FunctionComponent<
  EvaluationSuiteExperimentDetailsProps
> = ({ experiment, suiteId }) => {
  const experimentScores: FeedbackScoreDisplay[] = useMemo(() => {
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
  }, [experiment]);

  const experimentTracesSearch = useMemo(
    () => ({
      traces_filters: generateExperimentIdFilter(experiment.id),
    }),
    [experiment.id],
  );

  return (
    <div className="pb-4 pt-6">
      <div className="mb-4 flex min-h-8 items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          {experiment.name}
        </h1>
      </div>
      <div className="mb-1 flex gap-2 overflow-x-auto">
        <DateTag
          date={experiment.created_at}
          resource={RESOURCE_TYPE.experiment}
        />
        <NavigationTag
          id={experiment.dataset_id ?? suiteId}
          name={experiment.dataset_name ?? "Evaluation suite"}
          resource={RESOURCE_TYPE.evaluationSuite}
        />
        {experiment.prompt_versions?.length ? (
          <NavigationTag
            id={experiment.prompt_versions[0].prompt_id}
            name={experiment.prompt_versions[0].prompt_name}
            resource={RESOURCE_TYPE.prompt}
          />
        ) : null}
        {experiment.project_id && (
          <NavigationTag
            resource={RESOURCE_TYPE.traces}
            id={experiment.project_id}
            name="Traces"
            search={experimentTracesSearch}
            tooltipContent="View all traces for this experiment"
          />
        )}
      </div>
      <ExperimentTagsList
        tags={experiment.tags ?? []}
        experimentId={experiment.id}
        experiment={experiment}
      />
      <FeedbackScoresList scores={experimentScores} />
      <div className="mt-2 flex items-center gap-4 text-muted-slate">
        <span>Pass rate: {"\u2014"}</span>
      </div>
    </div>
  );
};

export default EvaluationSuiteExperimentDetails;
