import React, { useCallback } from "react";
import { ThumbsDown, ThumbsUp } from "lucide-react";

import ThumbDownFilled from "@/icons/thumbs-down-filled.svg?react";
import ThumbUpFilled from "@/icons/thumbs-up-filled.svg?react";
import { SESSION_FEEDBACK_VALUE } from "@/types/ai-assistant";
import useTraceAnalyzerFeedbackSetMutation from "@/api/ai-assistant/useTraceAnalyzerFeedbackSetMutation";
import useTraceAnalyzerFeedbackDeleteMutation from "@/api/ai-assistant/useTraceAnalyzerFeedbackDeleteMutation";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

type TraceSessionFeedbackProps = {
  state?: SESSION_FEEDBACK_VALUE;
  traceId: string;
  onOptimisticUpdate?: (value: SESSION_FEEDBACK_VALUE | null) => void;
};

const TraceSessionFeedback: React.FC<TraceSessionFeedbackProps> = ({
  state,
  traceId,
  onOptimisticUpdate,
}) => {
  const { mutate: updateMutation, isPending: isUpdatePending } =
    useTraceAnalyzerFeedbackSetMutation();
  const { mutate: deleteMutation, isPending: isDeletePending } =
    useTraceAnalyzerFeedbackDeleteMutation();

  const isPending = isUpdatePending || isDeletePending;

  const updateFeedback = useCallback(
    (value: SESSION_FEEDBACK_VALUE) => {
      const previousValue = state; // Capture current state for rollback
      // Immediately update parent state for instant UI response
      onOptimisticUpdate?.(value);
      updateMutation(
        {
          traceId,
          value,
        },
        {
          onError: () => {
            // Rollback to previous state on error
            onOptimisticUpdate?.(previousValue ?? null);
          },
        },
      );
    },
    [traceId, updateMutation, onOptimisticUpdate, state],
  );

  const deleteFeedback = useCallback(() => {
    const previousValue = state; // Capture current state for rollback
    // Immediately update parent state for instant UI response
    onOptimisticUpdate?.(null);
    deleteMutation(
      {
        traceId,
      },
      {
        onError: () => {
          // Rollback to previous state on error
          onOptimisticUpdate?.(previousValue ?? null);
        },
      },
    );
  }, [traceId, deleteMutation, onOptimisticUpdate, state]);

  return (
    <TooltipProvider>
      {state !== undefined ? (
        // If feedback is already given, show only the selected button
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon-2xs"
              disabled={isPending}
              onClick={deleteFeedback}
              aria-label={
                state === SESSION_FEEDBACK_VALUE.like
                  ? "Remove positive feedback"
                  : "Remove negative feedback"
              }
            >
              {state === SESSION_FEEDBACK_VALUE.like ? (
                <ThumbUpFilled className="text-muted-slate" />
              ) : (
                <ThumbDownFilled className="text-muted-slate" />
              )}
            </Button>
          </TooltipTrigger>
          <TooltipContent>Click to remove feedback</TooltipContent>
        </Tooltip>
      ) : (
        // If no feedback given yet, show both buttons
        <div className="flex flex-nowrap items-center gap-0.5">
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon-2xs"
                disabled={isPending}
                onClick={() => updateFeedback(SESSION_FEEDBACK_VALUE.like)}
                aria-label="Rate conversation positively"
              >
                <ThumbsUp />
              </Button>
            </TooltipTrigger>
            <TooltipContent>Rate conversation positively</TooltipContent>
          </Tooltip>

          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon-2xs"
                disabled={isPending}
                onClick={() => updateFeedback(SESSION_FEEDBACK_VALUE.dislike)}
                aria-label="Rate conversation negatively"
              >
                <ThumbsDown />
              </Button>
            </TooltipTrigger>
            <TooltipContent>Rate conversation negatively</TooltipContent>
          </Tooltip>
        </div>
      )}
    </TooltipProvider>
  );
};

export default TraceSessionFeedback;
