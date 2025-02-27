import React, { useCallback } from "react";
import { ThumbsDown, ThumbsUp } from "lucide-react";

import ThumbDownFilled from "@/icons/thumbs-down-filled.svg?react";
import ThumbUpFilled from "@/icons/thumbs-up-filled.svg?react";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import { Button } from "@/components/ui/button";

type LikeFeedbackProps = {
  state?: 1 | 0;
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
          state === 1 ? deleteFeedbackScore() : updateFeedbackScore(1);
        }}
      >
        {state === 1 ? (
          <ThumbUpFilled className="text-muted-slate" />
        ) : (
          <ThumbsUp />
        )}
      </Button>
      <Button
        variant="ghost"
        size="icon-2xs"
        onClick={() => {
          state === 0 ? deleteFeedbackScore() : updateFeedbackScore(0);
        }}
      >
        {state === 0 ? (
          <ThumbDownFilled className="text-muted-slate" />
        ) : (
          <ThumbsDown />
        )}
      </Button>
    </div>
  );
};

export default LikeFeedback;
