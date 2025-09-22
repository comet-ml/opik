import React, { useCallback } from "react";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import { Separator } from "@/components/ui/separator";
import { useSMEFlow } from "../SMEFlowContext";

const CommentAndScoreViewer: React.FC = () => {
  const {
    currentAnnotationState,
    annotationQueue,
    updateLocalComment,
    updateLocalFeedbackScore,
    deleteLocalFeedbackScore,
  } = useSMEFlow();

  const onSubmit = useCallback(
    (commentText: string) => {
      updateLocalComment(commentText);
    },
    [updateLocalComment],
  );

  const onUpdateFeedbackScore = useCallback(
    (score: UpdateFeedbackScoreData) => {
      updateLocalFeedbackScore(score);
    },
    [updateLocalFeedbackScore],
  );

  const onDeleteFeedbackScore = useCallback(
    (scoreId: string) => {
      deleteLocalFeedbackScore(scoreId);
    },
    [deleteLocalFeedbackScore],
  );

  return (
    <div className="-mx-6">
      <UserCommentForm
        onSubmit={(data) => onSubmit(data.commentText)}
        commentText={currentAnnotationState.comment?.text}
        className="px-6"
      >
        <UserCommentForm.TextareaField placeholder="Add a comment..." />
      </UserCommentForm>

      <div className="px-6">
        <Separator orientation="horizontal" className="my-4" />
      </div>

      <FeedbackScoresEditor
        feedbackScores={currentAnnotationState.scores}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
        feedbackDefinitionNames={annotationQueue?.feedback_definition_names}
        className="mt-4"
        header={
          <div className="flex items-center gap-1 pb-2">
            <span className="comet-body-s-accented truncate">
              Feedback scores
            </span>
          </div>
        }
      />
    </div>
  );
};

export default CommentAndScoreViewer;
