import React, { useCallback } from "react";
import { ThumbsDown, ThumbsUp } from "lucide-react";

import ThumbDownFilled from "@/icons/thumbs-down-filled.svg?react";
import ThumbUpFilled from "@/icons/thumbs-up-filled.svg?react";
import { USER_FEEDBACK_SCORE } from "@/types/traces";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import { Button } from "@/components/ui/button";

type LikeFeedbackProps = {
  state?: USER_FEEDBACK_SCORE;
  traceId: string;
};

const LikeFeedback: React.FC<LikeFeedbackProps> = ({ state, traceId }) => {
  const { mutate: updateMutation } = useTraceFeedbackScoreSetMutation();
  const { mutate: deleteMutation } = useTraceFeedbackScoreDeleteMutation();

  const updateFeedbackScore = useCallback(
    (value: number) => {
      updateMutation({
        name: USER_FEEDBACK_NAME,
        traceId,
        value,
      });
    },
    [traceId, updateMutation],
  );

  const deleteFeedbackScore = useCallback(() => {
    deleteMutation({
      traceId,
      name: USER_FEEDBACK_NAME,
    });
  }, [traceId, deleteMutation]);

  return (
    <div className="flex flex-nowrap items-center gap-0.5">
      <Button
        variant="ghost"
        size="icon-2xs"
        onClick={() => {
          state === USER_FEEDBACK_SCORE.like
            ? deleteFeedbackScore()
            : updateFeedbackScore(USER_FEEDBACK_SCORE.like);
        }}
      >
        {state === USER_FEEDBACK_SCORE.like ? (
          <ThumbUpFilled className="text-muted-slate" />
        ) : (
          <ThumbsUp />
        )}
      </Button>
      <Button
        variant="ghost"
        size="icon-2xs"
        onClick={() => {
          state === USER_FEEDBACK_SCORE.dislike
            ? deleteFeedbackScore()
            : updateFeedbackScore(USER_FEEDBACK_SCORE.dislike);
        }}
      >
        {state === USER_FEEDBACK_SCORE.dislike ? (
          <ThumbDownFilled className="text-muted-slate" />
        ) : (
          <ThumbsDown />
        )}
      </Button>
    </div>
  );
};

export default LikeFeedback;
