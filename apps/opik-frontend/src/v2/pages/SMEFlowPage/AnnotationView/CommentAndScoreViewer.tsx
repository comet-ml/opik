import React, { useRef } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { CircleCheck, Eye } from "lucide-react";
import FeedbackScoresEditor from "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/shared/UserComment/UserCommentForm";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { useSMEFlow, ITEM_STATE } from "@/v2/pages/SMEFlowPage/SMEFlowContext";
import { SME_ACTION, SME_HOTKEYS } from "@/v2/pages/SMEFlowPage/hotkeys";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  getAnnotationQueueItemId,
  isItemProcessedByUser,
} from "@/lib/annotation-queues";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";

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
    currentItemLockDenied,
    updateComment,
    updateFeedbackScore,
    deleteFeedbackScore,
  } = useSMEFlow();

  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const isCompleted = currentItem
    ? itemStates[getAnnotationQueueItemId(currentItem)] === ITEM_STATE.COMPLETED
    : false;

  const userHasAnnotated = currentItem
    ? isItemProcessedByUser(
        currentItem,
        annotationQueue?.feedback_definition_names ?? [],
        currentUserName,
      )
    : false;

  const isInReview = currentItem
    ? itemStates[getAnnotationQueueItemId(currentItem)] === ITEM_STATE.IN_REVIEW
    : false;

  const isLockedForUser =
    (isCompleted && !userHasAnnotated) || isInReview || currentItemLockDenied;

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const feedbackScoresRef = useRef<HTMLDivElement>(null);

  const hasFeedbackDefinitions =
    annotationQueue?.feedback_definition_names &&
    annotationQueue.feedback_definition_names.length > 0;

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].key,
    (keyboardEvent: KeyboardEvent) => {
      if (isLockedForUser) return;
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      textareaRef.current?.focus();
    },
    { enableOnFormTags: true },
    [isLockedForUser],
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
      if (!hasFeedbackDefinitions || isLockedForUser) return;
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      const firstInput = feedbackScoresRef.current?.querySelector(
        'input[type="number"], input[type="text"], textarea, button',
      ) as HTMLElement;
      firstInput?.focus();
    },
    { enableOnFormTags: true },
    [hasFeedbackDefinitions, isLockedForUser],
  );

  if (isInReview || currentItemLockDenied) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-12 text-center text-muted-slate">
        <Eye className="size-5 text-orange-400" />
        <p className="comet-body-xs max-w-[250px]">
          This item is currently being reviewed by another annotator
        </p>
      </div>
    );
  }

  if (isLockedForUser) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-12 text-center text-muted-slate">
        <CircleCheck className="size-5 text-success" />
        <p className="comet-body-xs max-w-[250px]">
          This item has already been scored by the required number of annotators
        </p>
      </div>
    );
  }

  return (
    <div>
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
            placeholder="Add a comment..."
            value={currentAnnotationState.comment?.text || ""}
            onValueChange={updateComment}
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
