import React, { useRef } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import { HotkeyDisplay } from "@/components/ui/hotkey-display";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { useSMEFlow } from "@/components/pages/SMEFlowPage/SMEFlowContext";
import {
  SME_ACTION,
  SME_HOTKEYS,
} from "@/components/pages/SMEFlowPage/hotkeys";

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
    updateComment,
    updateFeedbackScore,
    deleteFeedbackScore,
  } = useSMEFlow();

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const feedbackScoresRef = useRef<HTMLDivElement>(null);

  // Check if feedback definitions exist
  const hasFeedbackDefinitions =
    annotationQueue?.feedback_definition_names &&
    annotationQueue.feedback_definition_names.length > 0;

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].key,
    (keyboardEvent: KeyboardEvent) => {
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      textareaRef.current?.focus();
    },
    { enableOnFormTags: true },
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.BLUR_COMMENT].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      textareaRef.current?.blur();
    },
    { enableOnFormTags: true },
  );

  // Only register feedback scores hotkey if there are feedback definitions
  useHotkeys(
    SME_HOTKEYS[SME_ACTION.FOCUS_FEEDBACK_SCORES].key,
    (keyboardEvent: KeyboardEvent) => {
      if (!hasFeedbackDefinitions) return;
      if (isFromEditableElement(keyboardEvent)) return;
      keyboardEvent.preventDefault();
      const firstInput = feedbackScoresRef.current?.querySelector(
        'input[type="number"], input[type="text"], textarea, button',
      ) as HTMLElement;
      firstInput?.focus();
    },
    { enableOnFormTags: true },
    [hasFeedbackDefinitions],
  );

  return (
    <div className="pl-4">
      <div className="flex items-center justify-between gap-1 pb-2">
        <span className="comet-body-s-accented truncate">Comment</span>
        <TooltipWrapper
          content={SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].description}
          hotkeys={[SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].display]}
        >
          <HotkeyDisplay
            hotkey={SME_HOTKEYS[SME_ACTION.FOCUS_COMMENT].display}
            variant="outline"
            size="sm"
            className="size-6 border border-gray-300 bg-white p-0 font-mono text-xs shadow-sm"
          />
        </TooltipWrapper>
      </div>
      <UserCommentForm.StandaloneTextareaField
        ref={textareaRef}
        placeholder="Add a comment..."
        value={currentAnnotationState.comment?.text || ""}
        onValueChange={updateComment}
      />
      {hasFeedbackDefinitions && (
        <div ref={feedbackScoresRef} className="relative mt-6">
          <FeedbackScoresEditor
            key={currentItem?.id}
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
                    size="sm"
                    className="size-6 border border-gray-300 bg-white p-0 font-mono text-xs shadow-sm"
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
