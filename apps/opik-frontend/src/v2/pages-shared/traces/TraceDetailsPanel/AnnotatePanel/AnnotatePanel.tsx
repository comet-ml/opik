import React, { useCallback, useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import { isObjectSpan } from "@/lib/traces";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useSpanCommentsBatchDeleteMutation from "@/api/traces/useSpanCommentsBatchDeleteMutation";
import useCreateSpanCommentMutation from "@/api/traces/useCreateSpanCommentMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateSpanCommentMutation from "@/api/traces/useUpdateSpanCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { UpdateFeedbackScoreData } from "../TraceAnnotateViewer/types";
import { extractSpanMetadataFromValueByAuthor } from "../TraceDataViewer/FeedbackScoreTable/utils";
import FeedbackScoresEditor from "../../FeedbackScoresEditor/FeedbackScoresEditor";
import CommentsSection from "@/shared/UserComment/CommentsSection";
import { Separator } from "@/ui/separator";

type AnnotatePanelProps = {
  data: Trace | Span;
  traceId: string;
  projectId: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
};

const isSpan = (d: Trace | Span): d is Span => isObjectSpan(d);

const AnnotatePanel: React.FC<AnnotatePanelProps> = ({
  data,
  traceId,
  projectId,
  activeSection,
  setActiveSection,
}) => {
  const spanId = isSpan(data) ? data.id : undefined;
  const isTrace = !spanId;
  const hasFeedbackScores = Boolean(data.feedback_scores?.length);

  const filteredFeedbackScores = useMemo(
    () => data.feedback_scores || [],
    [data.feedback_scores],
  );

  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: feedbackScoreDelete } = useTraceFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = useCallback(
    (update: UpdateFeedbackScoreData) => {
      setTraceFeedbackScore({ ...update, traceId, spanId });
    },
    [setTraceFeedbackScore, traceId, spanId],
  );

  const onDeleteFeedbackScore = useCallback(
    (name: string, author?: string, spanIdToDelete?: string) => {
      let targetSpanId = spanIdToDelete ?? spanId;
      if (isTrace && !spanIdToDelete) {
        const score = filteredFeedbackScores.find((s) => s.name === name);
        if (score?.value_by_author) {
          const metadata = extractSpanMetadataFromValueByAuthor(
            score.value_by_author,
          );
          targetSpanId = metadata.span_id;
        }
      }
      feedbackScoreDelete({ name, traceId, spanId: targetSpanId, author });
    },
    [isTrace, spanId, filteredFeedbackScores, feedbackScoreDelete, traceId],
  );

  const { mutate: deleteTraceComments } = useTraceCommentsBatchDeleteMutation();
  const { mutate: deleteSpanComments } = useSpanCommentsBatchDeleteMutation();
  const { mutate: createSpanComment } = useCreateSpanCommentMutation();
  const { mutate: createTraceComment } = useCreateTraceCommentMutation();
  const { mutate: updateSpanComment } = useUpdateSpanCommentMutation();
  const { mutate: updateTraceComment } = useUpdateTraceCommentMutation();

  const onCommentSubmit = useCallback(
    (text: string) => {
      if (!isSpan(data)) {
        createTraceComment({ text, traceId });
        return;
      }
      createSpanComment({ text, spanId: data.id, projectId });
    },
    [data, traceId, projectId, createTraceComment, createSpanComment],
  );

  const onCommentEdit = useCallback(
    (commentId: string, text: string) => {
      if (!isSpan(data)) {
        updateTraceComment({ text, commentId, traceId });
        return;
      }
      updateSpanComment({ text, commentId, projectId });
    },
    [data, traceId, projectId, updateTraceComment, updateSpanComment],
  );

  const onCommentDelete = useCallback(
    (commentId: string) => {
      if (!isSpan(data)) {
        deleteTraceComments({ ids: [commentId], traceId });
        return;
      }
      deleteSpanComments({ ids: [commentId], projectId });
    },
    [data, traceId, projectId, deleteTraceComments, deleteSpanComments],
  );

  return (
    <DetailsActionSectionLayout
      title="Annotate"
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      <div className="size-full overflow-y-auto">
        {hasFeedbackScores && (
          <>
            <div className="comet-body-s-accented truncate px-4 pt-4">
              Feedback scores
            </div>
            <div className="flex flex-wrap gap-2 px-4 py-2">
              {filteredFeedbackScores.map((score) => (
                <FeedbackScoreTag
                  key={score.name}
                  label={score.name}
                  value={score.value}
                  reason={score.reason}
                  lastUpdatedAt={score.last_updated_at}
                  lastUpdatedBy={score.last_updated_by}
                  valueByAuthor={score.value_by_author}
                  category={score.category_name}
                />
              ))}
            </div>
          </>
        )}
        <FeedbackScoresEditor
          key={`${traceId}-${spanId}`}
          feedbackScores={filteredFeedbackScores}
          onUpdateFeedbackScore={onUpdateFeedbackScore}
          onDeleteFeedbackScore={onDeleteFeedbackScore}
          className="mt-4 px-4"
          header={<FeedbackScoresEditor.Header title="Human review" />}
          footer={
            <FeedbackScoresEditor.Footer
              entityCopy={isTrace ? "traces" : "spans"}
            />
          }
        />

        <Separator className="m-4 w-auto" />

        <div className="comet-body-s-accented truncate px-4">Comments</div>
        <CommentsSection
          comments={data.comments ?? []}
          onSubmit={onCommentSubmit}
          onEditSubmit={onCommentEdit}
          onDelete={onCommentDelete}
          formClassName="mt-2 px-4"
          listClassName="mt-3 h-full overflow-auto pb-3"
          commentClassName="px-4 hover:bg-soft-background"
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default AnnotatePanel;
