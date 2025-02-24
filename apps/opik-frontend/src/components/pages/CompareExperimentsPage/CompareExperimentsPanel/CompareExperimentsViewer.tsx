import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import { FlaskConical, ListTree } from "lucide-react";

import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import FeedbackScoresEditor from "@/components/shared/FeedbackScoresEditor/FeedbackScoresEditor";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import NoData from "@/components/shared/NoData/NoData";
import useExperimentById from "@/api/datasets/useExperimentById";
import { TraceFeedbackScore } from "@/types/traces";
import { Experiment, ExperimentItem } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { traceExist } from "@/lib/traces";
import ExperimentCommentsViewerCore from "./DataTab/ExperimentCommentsViewer";

type CompareExperimentsViewerProps = {
  experimentItem: ExperimentItem;
  openTrace: OnChangeFn<string>;
  experiments: Experiment[];
};

const CompareExperimentsViewer: React.FunctionComponent<
  CompareExperimentsViewerProps
> = ({ experimentItem, openTrace, experiments }) => {
  const isTraceExist = traceExist(experimentItem);
  const experimentId = experimentItem.experiment_id;
  const { data } = useExperimentById(
    {
      experimentId,
    },
    {
      refetchOnMount: false,
    },
  );

  const name = data?.name || experimentId;
  const comments =
    experiments.find((exp) => exp.id === experimentId)?.comments || [];

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(experimentItem.feedback_scores || [], "name"),
    [experimentItem.feedback_scores],
  );

  const onExpandClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (isFunction(openTrace) && experimentItem.trace_id) {
      openTrace(experimentItem.trace_id);
    }
  };

  const renderOutput = () => {
    if (!isTraceExist) {
      return (
        <div className="mt-64">
          <NoData
            title="No related trace found"
            message="It looks like it was deleted or not created"
            className="min-h-24 text-center"
          />
        </div>
      );
    }

    if (experimentItem.output) {
      return <SyntaxHighlighter data={experimentItem.output} />;
    }

    return null;
  };

  return (
    <div className="relative flex h-full flex-col px-3 pt-6">
      <div className="flex items-center justify-between gap-1 pb-4">
        <TooltipWrapper content={name}>
          <div className="flex items-center gap-2 overflow-hidden">
            <FlaskConical className="size-4 shrink-0 text-muted-slate" />
            <h2 className="comet-body-accented truncate">{name}</h2>
          </div>
        </TooltipWrapper>
        {isTraceExist && (
          <TooltipWrapper content="Click to open original trace">
            <Button
              size="sm"
              variant="outline"
              onClick={onExpandClick}
              className="shrink-0"
            >
              <ListTree className="mr-2 size-4 shrink-0" />
              Trace
            </Button>
          </TooltipWrapper>
        )}
      </div>

      {renderOutput()}

      {isTraceExist && (
        <div className="sticky bottom-0 right-0 mt-auto box-border max-h-[40vh] overflow-auto border-t bg-white py-4 contain-content">
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            traceId={experimentItem.trace_id as string}
          />
          <ExperimentCommentsViewerCore
            comments={comments}
            traceId={experimentItem.trace_id as string}
          />
        </div>
      )}
    </div>
  );
};

export default CompareExperimentsViewer;
