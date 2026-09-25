import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import isEmpty from "lodash/isEmpty";
import { FlaskConical, ListTree } from "lucide-react";

import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import AttachmentsList from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import { useExperimentItemMedia } from "@/hooks/useExperimentItemMedia";
import ExperimentMessagesViewer from "@/v2/pages-shared/experiments/ExperimentMessagesViewer/ExperimentMessagesViewer";
import ExperimentFeedbackScoresViewer from "@/v2/pages-shared/ExperimentFeedbackScoresViewer/ExperimentFeedbackScoresViewer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import NoData from "@/shared/NoData/NoData";
import useExperimentById from "@/api/datasets/useExperimentById";
import { TraceFeedbackScore } from "@/types/traces";
import { ExperimentItem } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/ui/button";
import { traceExist, traceVisible } from "@/lib/traces";
import ExperimentCommentsViewer from "./DataTab/ExperimentCommentsViewer";
import { splitOutputForMessages } from "./splitOutputForMessages";
import { CommentItems } from "@/types/comment";

type CompareExperimentsViewerProps = {
  experimentItem: ExperimentItem;
  openTrace: OnChangeFn<string>;
  sectionIdx: number;
};

const CompareExperimentsViewer: React.FunctionComponent<
  CompareExperimentsViewerProps
> = ({ experimentItem, openTrace, sectionIdx }) => {
  const isTraceExist = traceExist(experimentItem);
  const isTraceVisible = traceVisible(experimentItem);
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

  const { media, transformedOutput } = useExperimentItemMedia({
    output: experimentItem.output,
    traceId: experimentItem.trace_id,
    projectId: data?.project_id,
  });

  const inputAndOutput = useMemo(
    () => ({ input: experimentItem.input, output: experimentItem.output }),
    [experimentItem.input, experimentItem.output],
  );

  // Extracted in one pass so input and output media share one placeholder
  // numbering: separate passes both start at [image_0], and the provider would
  // then resolve the output's [image_0] to the input's picture.
  const {
    media: inputAndOutputMedia,
    transformedOutput: transformedInputAndOutput,
  } = useExperimentItemMedia({ output: inputAndOutput });

  const { input: messagesInput, output: messagesOutput } =
    transformedInputAndOutput as typeof inputAndOutput;

  const messagesMedia = useMemo(
    () => [
      ...inputAndOutputMedia,
      ...media.filter((item) => item.source === "attachment"),
    ],
    [inputAndOutputMedia, media],
  );

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(experimentItem.feedback_scores || [], "name"),
    [experimentItem.feedback_scores],
  );

  const comments: CommentItems = useMemo(
    () => experimentItem.comments || [],
    [experimentItem.comments],
  );

  // Gated on the output alone: mapAndCombineMessages silently drops a side it
  // does not recognise, and this panel is the only place the output is shown.
  const { rendersAsMessages, remainingOutput } = useMemo(
    () => splitOutputForMessages(messagesOutput),
    [messagesOutput],
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

    if (!experimentItem.output) {
      return null;
    }

    if (!rendersAsMessages) {
      const highlighter = (
        <SyntaxHighlighter
          data={transformedOutput as object}
          prettifyConfig={{ fieldType: "output" }}
          preserveKey={`syntax-highlighter-compare-experiment-output-${sectionIdx}`}
        />
      );

      if (!media.length) {
        return highlighter;
      }

      return (
        <MediaProvider media={media}>
          <div className="flex flex-col gap-2">
            <AttachmentsList media={media} />
            {highlighter}
          </div>
        </MediaProvider>
      );
    }

    return (
      <MediaProvider media={messagesMedia}>
        <div className="flex flex-col gap-2">
          {media.length > 0 && <AttachmentsList media={media} />}
          <ExperimentMessagesViewer
            input={messagesInput}
            output={messagesOutput}
            preserveKey={`compare-experiment-messages-${sectionIdx}`}
          />
          {!isEmpty(remainingOutput) && (
            <SyntaxHighlighter
              data={remainingOutput}
              prettifyConfig={{ fieldType: "output" }}
              preserveKey={`syntax-highlighter-compare-experiment-output-remaining-${sectionIdx}`}
            />
          )}
        </div>
      </MediaProvider>
    );
  };

  return (
    <div className="relative flex h-full flex-col px-6 pt-4">
      <div className="flex items-center justify-between gap-1 pb-4">
        <TooltipWrapper content={name}>
          <div className="flex items-center gap-2 overflow-hidden">
            <FlaskConical className="size-4 shrink-0 text-muted-slate" />
            <h2 className="comet-body-accented truncate">{name}</h2>
          </div>
        </TooltipWrapper>
        {isTraceExist && isTraceVisible && (
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
        <div className="sticky bottom-0 right-0 mt-auto flex max-h-[50vh] shrink-0 flex-col bg-background contain-content">
          <div className="box-border flex min-h-14 shrink grow flex-col border-y">
            <ExperimentFeedbackScoresViewer
              feedbackScores={feedbackScores}
              traceId={experimentItem.trace_id as string}
              sectionIdx={sectionIdx}
            />
          </div>

          <div className="flex max-h-[35vh] min-h-14 shrink grow flex-col">
            <ExperimentCommentsViewer
              comments={comments}
              traceId={experimentItem.trace_id as string}
              sectionIdx={sectionIdx}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default CompareExperimentsViewer;
