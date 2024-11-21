import React, { useEffect, useMemo } from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import { FlaskConical, ListTree } from "lucide-react";

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

const SCORES_EDITOR_HEIGHT = "160px";

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

  const renderOutput = () => {
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
    <div className="relative py-6 px-3 h-full">
      <div className="flex items-center justify-between gap-2 pb-4">
        <TooltipWrapper content={name}>
          <div className="flex items-center gap-2">
            <FlaskConical className="size-4 shrink-0 text-muted-slate" />
            <h2 className="comet-body-accented truncate flex">{name}</h2>
          </div>
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

      <div className={`h-[calc(100%-${SCORES_EDITOR_HEIGHT})] overflow-auto`}>
        {renderOutput()}
      </div>

      {isTraceExist && (
        <div
          className={`pt-4 pb-8 contain-content box-border overflow-auto h-[${SCORES_EDITOR_HEIGHT}]`}
          style={{ height: SCORES_EDITOR_HEIGHT }}
        >
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            traceId={experimentItem.trace_id as string}
          />
        </div>
      )}
    </div>
  );
};

export default CompareExperimentsViewer;
