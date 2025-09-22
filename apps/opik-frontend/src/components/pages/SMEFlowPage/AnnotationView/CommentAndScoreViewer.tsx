import React, { useCallback, useState } from "react";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import { Thread, Trace, TraceFeedbackScore } from "@/types/traces";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import { Separator } from "@/components/ui/separator";
import { CommentItem } from "@/types/comment";

type Xxx = {
  comment?: CommentItem;
  scores: TraceFeedbackScore[];
};

type CommentAndScoreViewerProps = {
  item?: Trace | Thread;
};

// TODO lala we need only current user comments and feedback scores.

const CommentAndScoreViewer: React.FC<CommentAndScoreViewerProps> = ({
  item,
}) => {
  const [x] = useState<Xxx>({
    scores: [],
  });

  const onSubmit = useCallback((commentText: string) => {
    console.log("Submitting comment:", commentText);
    // TODO: Implement comment submission logic
  }, []);

  const onUpdateFeedbackScore = useCallback(
    (score: UpdateFeedbackScoreData) => {
      console.log("Updating feedback score:", score);
      // TODO: Implement feedback score update logic
    },
    [],
  );

  const onDeleteFeedbackScore = useCallback((scoreId: string) => {
    console.log("Deleting feedback score:", scoreId);
    // TODO: Implement feedback score deletion logic
  }, []);

  return (
    <div className="-mx-6">
      <UserCommentForm
        onSubmit={(data) => onSubmit(data.commentText)}
        className="px-6"
      >
        <UserCommentForm.TextareaField placeholder="Add a comment..." />
      </UserCommentForm>

      <div className="px-6">
        <Separator orientation="horizontal" className="my-4" />
      </div>

      <FeedbackScoresEditor
        key={item?.id}
        feedbackScores={x.scores}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
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
