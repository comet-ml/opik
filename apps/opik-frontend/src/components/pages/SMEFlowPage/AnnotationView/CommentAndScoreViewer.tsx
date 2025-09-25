import React from "react";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import { Separator } from "@/components/ui/separator";
import { useSMEFlow } from "../SMEFlowContext";

const CommentAndScoreViewer: React.FC = () => {
  const {
    currentAnnotationState,
    annotationQueue,
    updateComment,
    updateFeedbackScore,
    deleteFeedbackScore,
  } = useSMEFlow();

  return (
    <div className="pl-4">
      <UserCommentForm.StandaloneTextareaField
        placeholder="Add a comment..."
        value={currentAnnotationState.comment?.text || ""}
        onValueChange={updateComment}
      />

      <Separator orientation="horizontal" className="my-4" />

      <FeedbackScoresEditor
        feedbackScores={currentAnnotationState.scores}
        onUpdateFeedbackScore={updateFeedbackScore}
        onDeleteFeedbackScore={deleteFeedbackScore}
        feedbackDefinitionNames={annotationQueue?.feedback_definition_names}
        className="mt-4 px-0"
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
