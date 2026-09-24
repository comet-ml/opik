import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import { FlaskConical, ListTree } from "lucide-react";

import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import AttachmentsList from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import {
  MediaProvider,
  mapAndCombineMessages,
} from "@/shared/PrettyLLMMessage/llmMessages";
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

  // The input carries its own placeholders, which stay unresolved literals in
  // the messages view unless their media is extracted too. Attachments are
  // already requested for the trace above, so this pass only handles inline media.
  const { media: inputMedia, transformedOutput: transformedInput } =
    useExperimentItemMedia({ output: experimentItem.input });

  const combinedMedia = useMemo(
    () => [...inputMedia, ...media],
    [inputMedia, media],
  );

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(experimentItem.feedback_scores || [], "name"),
    [experimentItem.feedback_scores],
  );

  const comments: CommentItems = useMemo(
    () => experimentItem.comments || [],
    [experimentItem.comments],
  );

  // The trace-style renderer only applies when the payload is a recognised LLM
  // message format; anything else keeps the JSON/YAML/pretty viewer. Detection
  // runs on the media-resolved output so it matches what actually gets rendered.
  const hasMessages = useMemo(
    () =>
      mapAndCombineMessages(transformedInput, transformedOutput).messages
        .length > 0,
    [transformedInput, transformedOutput],
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

    const body = hasMessages ? (
      <ExperimentMessagesViewer
        input={transformedInput}
        output={transformedOutput}
        preserveKey={`compare-experiment-messages-${sectionIdx}`}
      />
    ) : (
      <SyntaxHighlighter
        data={transformedOutput as object}
        prettifyConfig={{ fieldType: "output" }}
        preserveKey={`syntax-highlighter-compare-experiment-output-${sectionIdx}`}
      />
    );

    if (!combinedMedia.length) {
      return body;
    }

    // The provider resolves placeholders from either side, while the attachments
    // list stays scoped to the output media it has always shown.
    return (
      <MediaProvider media={combinedMedia}>
        <div className="flex flex-col gap-2">
          {media.length > 0 && <AttachmentsList media={media} />}
          {body}
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
