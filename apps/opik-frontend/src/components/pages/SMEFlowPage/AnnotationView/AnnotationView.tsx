import React from "react";
import { ChevronLeft, ChevronRight, Check } from "lucide-react";
import { useHotkeys } from "react-hotkeys-hook";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import TraceDataViewer from "./TraceDataViewer";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { HotkeyDisplay } from "@/components/ui/hotkey-display";
import CommentAndScoreViewer from "@/components/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";
import ValidationAlert from "./ValidationAlert";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useSMEFlow } from "../SMEFlowContext";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import ThreadDataViewer from "./ThreadDataViewer";
import { SME_ACTION, SME_HOTKEYS } from "../hotkeys";
import { AnnotationTreeStateProvider } from "./AnnotationTreeStateContext";

interface AnnotationViewProps {
  header: React.ReactNode;
}

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  header,
}) => {
  const {
    annotationQueue,
    currentIndex,
    queueItems,
    validationState,
    isCurrentItemProcessed,
    unprocessedItems,
    handleNext,
    handlePrevious,
    handleSubmit,
  } = useSMEFlow();

  const isLastItem = currentIndex === queueItems.length - 1;
  const isFirstItem = currentIndex === 0;

  // Determine button label based on item completion status
  const getButtonLabel = () => {
    if (isCurrentItemProcessed) {
      // Viewing a completed item (current item is NOT in unprocessedItems)
      // Check if there are OTHER unprocessed items
      // Since current item is already processed, it's not in unprocessedItems
      const hasOtherUnprocessedItems = unprocessedItems.length > 0;
      return hasOtherUnprocessedItems ? "Update + next" : "Update + complete";
    } else {
      // Viewing a non-completed item (current item IS in unprocessedItems)
      // Show "Submit + complete" only if this is the ONLY unprocessed item
      // Note: unprocessedItems includes items with cached unsaved changes
      return unprocessedItems.length === 1
        ? "Submit + complete"
        : "Submit + next";
    }
  };

  const buttonLabel = getButtonLabel();

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.PREVIOUS].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isFirstItem) {
        handlePrevious();
      }
    },
    [isFirstItem, handlePrevious],
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.NEXT].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isLastItem) {
        handleNext();
      }
    },
    [isLastItem, handleNext],
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.DONE].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (validationState.canSubmit) {
        handleSubmit();
      }
    },
    [validationState.canSubmit, handleSubmit],
  );

  const isThread = annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

  return (
    <AnnotationTreeStateProvider>
      <SMEFlowLayout
        header={header}
        footer={
          <>
            <ReturnToAnnotationQueueButton />
            <div className="flex items-center gap-2">
              <div
                className={cn(
                  "comet-body-s flex items-center",
                  isCurrentItemProcessed
                    ? "text-[var(--special-button)]"
                    : "text-light-slate",
                )}
              >
                {isCurrentItemProcessed && <Check className="mr-1 size-4" />}
                {currentIndex + 1} of {queueItems.length}
              </div>
              <TooltipWrapper
                content="Previous item"
                hotkeys={[SME_HOTKEYS[SME_ACTION.PREVIOUS].display]}
              >
                <Button
                  variant="outline"
                  onClick={handlePrevious}
                  disabled={isFirstItem}
                >
                  <ChevronLeft className="mr-2 size-4" />
                  Previous
                  <HotkeyDisplay
                    hotkey={SME_HOTKEYS[SME_ACTION.PREVIOUS].display}
                    variant="outline"
                    size="sm"
                    className="ml-2"
                  />
                </Button>
              </TooltipWrapper>
              <TooltipWrapper
                content="Next item"
                hotkeys={[SME_HOTKEYS[SME_ACTION.NEXT].display]}
              >
                <Button
                  variant="outline"
                  onClick={handleNext}
                  disabled={isLastItem}
                >
                  <HotkeyDisplay
                    hotkey={SME_HOTKEYS[SME_ACTION.NEXT].display}
                    variant="outline"
                    size="sm"
                    className="mr-2"
                  />
                  Next
                  <ChevronRight className="ml-2 size-4" />
                </Button>
              </TooltipWrapper>
              <TooltipWrapper
                content="Submit and continue"
                hotkeys={[SME_HOTKEYS[SME_ACTION.DONE].display]}
              >
                <Button
                  onClick={handleSubmit}
                  disabled={!validationState.canSubmit}
                >
                  {buttonLabel}
                  <HotkeyDisplay
                    hotkey={SME_HOTKEYS[SME_ACTION.DONE].display}
                    size="sm"
                    className="ml-2"
                  />
                </Button>
              </TooltipWrapper>
            </div>
          </>
        }
      >
        <div className="flex h-full flex-col">
          {validationState.errors.length > 0 && (
            <div className="mb-4">
              <ValidationAlert errors={validationState.errors} />
            </div>
          )}
          <Card className="flex h-full flex-row items-stretch p-6">
            <div className="flex-[2] overflow-y-auto">
              {isThread ? <ThreadDataViewer /> : <TraceDataViewer />}
            </div>
            <Separator orientation="vertical" className="mx-3" />
            <div className="flex-[1] overflow-y-auto">
              <CommentAndScoreViewer />
            </div>
          </Card>
        </div>
      </SMEFlowLayout>
    </AnnotationTreeStateProvider>
  );
};

export default AnnotationView;
