import React, { useRef } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { cn } from "@/lib/utils";
import FeedbackScoresEditor from "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/shared/UserComment/UserCommentForm";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { useSMEFlow, ITEM_STATE } from "@/v2/pages/SMEFlowPage/SMEFlowContext";
import { SME_ACTION, SME_HOTKEYS } from "@/v2/pages/SMEFlowPage/hotkeys";
import { usePermissions } from "@/contexts/PermissionsContext";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";

const isFromEditableElement = (keyboardEvent: KeyboardEvent): boolean => {
  const target = keyboardEvent.target as HTMLElement;
  if (!target) return false;

  return (
    target.tagName === "INPUT" ||
    target.tagName === "TEXTAREA" ||
    target.contentEditable === "true" ||
    target.isContentEditable
  );
};

const CommentAndScoreViewer: React.FC = () => {
  const {
    currentItem,
    currentAnnotationState,
    annotationQueue,
    itemStates,
    updateComment,
    updateFeedbackScore,
    deleteFeedbackScore,
  } = useSMEFlow();

  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const isCompleted = currentItem
    ? itemStates[getAnnotationQueueItemId(currentItem)] === ITEM_STATE.COMPLETED
    : false;

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const feedbackScoresRef = useRef<HTMLDivElement>(null);

  const hasFeedbackDefinitions =
    annotationQueue?.feedback_definition_names &&
    annotationQueue.feedback_definition_names.length > 0;

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].key,
    (keyboardEvent: KeyboardEvent) => {
      if (isCompleted) return;
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      textareaRef.current?.focus();
    },
    { enableOnFormTags: true },
    [isCompleted],
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.BLUR_COMMENT].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      textareaRef.current?.blur();
    },
    { enableOnFormTags: true },
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.FOCUS_FEEDBACK_SCORES].key,
    (keyboardEvent: KeyboardEvent) => {
      if (!hasFeedbackDefinitions || isCompleted) return;
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      const firstInput = feedbackScoresRef.current?.querySelector(
        'input[type="number"], input[type="text"], textarea, button',
      ) as HTMLElement;
      firstInput?.focus();
    },
    { enableOnFormTags: true },
    [hasFeedbackDefinitions, isCompleted],
  );

  return (
    <div
      className={cn(
        isCompleted &&
          "cursor-not-allowed opacity-50 [&_*]:pointer-events-none",
      )}
    >
      {canAnnotateTraceSpanThread && (
        <>
          <div className="flex items-center justify-between gap-1 pb-2">
            <span className="comet-body-s-accented truncate">Comments</span>
            <TooltipWrapper
              content={SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].description}
              hotkeys={[SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].display]}
            >
              <HotkeyDisplay
                hotkey={SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].display}
                variant="outline"
                size="xs"
              />
            </TooltipWrapper>
          </div>
          <UserCommentForm.StandaloneTextareaField
            ref={textareaRef}
            placeholder={
              isCompleted ? "This item has been completed" : "Add a comment..."
            }
            value={currentAnnotationState.comment?.text || ""}
            onValueChange={updateComment}
            disabled={isCompleted}
          />
        </>
      )}
      {hasFeedbackDefinitions && (
        <div ref={feedbackScoresRef} className="relative mt-4 pt-4">
          <FeedbackScoresEditor
            key={currentItem?.id}
            feedbackScores={currentAnnotationState.scores}
            onUpdateFeedbackScore={updateFeedbackScore}
            onDeleteFeedbackScore={deleteFeedbackScore}
            feedbackDefinitionNames={annotationQueue?.feedback_definition_names}
            className="px-0"
            header={
              <div className="flex items-center gap-1 pb-2">
                <span className="comet-body-s-accented truncate">
                  Feedback scores
                </span>
                <ExplainerIcon
                  {...EXPLAINERS_MAP[EXPLAINER_ID.feedback_scores_hotkeys]}
                />
                <div className="flex-auto" />
                <TooltipWrapper
                  content={
                    SME_HOTKEYS[SME_ACTION.FOCUS_FEEDBACK_SCORES].description
                  }
                  hotkeys={[
                    SME_HOTKEYS[SME_ACTION.FOCUS_FEEDBACK_SCORES].display,
                  ]}
                >
                  <HotkeyDisplay
                    hotkey={
                      SME_HOTKEYS[SME_ACTION.FOCUS_FEEDBACK_SCORES].display
                    }
                    variant="outline"
                    size="xs"
                  />
                </TooltipWrapper>
              </div>
            }
          />
        </div>
      )}
    </div>
  );
};

export default CommentAndScoreViewer;
