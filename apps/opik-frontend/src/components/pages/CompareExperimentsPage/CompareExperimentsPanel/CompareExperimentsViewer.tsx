import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import { ListTree } from "lucide-react";

import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import FeedbackScoresEditor from "@/components/shared/FeedbackScoresEditor/FeedbackScoresEditor";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import NoData from "@/components/shared/NoData/NoData";
import useExperimentById from "@/api/datasets/useExperimentById";
import { TraceFeedbackScore } from "@/types/traces";
import { ExperimentItem } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { traceExist } from "@/lib/traces";

type CompareExperimentsViewerProps = {
  experimentItem: ExperimentItem;
  openTrace: OnChangeFn<string>;
};

const CompareExperimentsViewer: React.FunctionComponent<
  CompareExperimentsViewerProps
> = ({ experimentItem, openTrace }) => {
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

  const renderContent = () => {
    if (!isTraceExist) {
      return (
        <NoData
          title="No related trace found"
          message="It looks like it was deleted or not created"
          className="absolute inset-0 min-h-24"
        />
      );
    }

    if (experimentItem.output) {
      return <SyntaxHighlighter data={experimentItem.output} />;
    }

    return null;
  };

  return (
    <div className="relative size-full p-6">
      <div className="flex items-center justify-between gap-2">
        <TooltipWrapper content={name}>
          <h2 className="comet-title-m truncate">Output: {name}</h2>
        </TooltipWrapper>
        {isTraceExist && (
          <TooltipWrapper content="Click to open original trace">
            <Button
              size="icon-sm"
              variant="outline"
              onClick={onExpandClick}
              className="shrink-0"
            >
              <ListTree className="size-4" />
            </Button>
          </TooltipWrapper>
        )}
      </div>
      {isTraceExist && (
        <div className="my-6">
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            traceId={experimentItem.trace_id as string}
          />
        </div>
      )}

      {renderContent()}
    </div>
  );
};

export default CompareExperimentsViewer;
