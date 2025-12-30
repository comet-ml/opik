import React, { useState, useCallback } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useHotkeys } from "react-hotkeys-hook";
import { Card } from "@/components/ui/card";
import TraceDataViewer from "./TraceDataViewer";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { HotkeyDisplay } from "@/components/ui/hotkey-display";
import CommentAndScoreViewer from "@/components/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";
import ValidationAlert from "./ValidationAlert";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
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
    isLastUnprocessedItem,
    hasChanges,
    handleNext,
    handlePrevious,
    handleSubmit,
    discardChanges,
  } = useSMEFlow();

  const isLastItem = currentIndex === queueItems.length - 1;
  const isFirstItem = currentIndex === 0;

  // State for unsaved changes confirmation dialog
  const [showUnsavedDialog, setShowUnsavedDialog] = useState(false);
  const [pendingNavigation, setPendingNavigation] = useState<
    "next" | "previous" | null
  >(null);

  const handleNavigateNext = useCallback(() => {
    if (hasChanges) {
      setPendingNavigation("next");
      setShowUnsavedDialog(true);
    } else {
      handleNext();
    }
  }, [hasChanges, handleNext]);

  const handleNavigatePrevious = useCallback(() => {
    if (hasChanges) {
      setPendingNavigation("previous");
      setShowUnsavedDialog(true);
    } else {
      handlePrevious();
    }
  }, [hasChanges, handlePrevious]);

  const handleConfirmDiscard = useCallback(() => {
    discardChanges();
    if (pendingNavigation === "next") {
      handleNext();
    } else if (pendingNavigation === "previous") {
      handlePrevious();
    }
    setPendingNavigation(null);
    setShowUnsavedDialog(false);
  }, [pendingNavigation, discardChanges, handleNext, handlePrevious]);

  const handleCancelNavigation = useCallback(() => {
    setPendingNavigation(null);
    setShowUnsavedDialog(false);
  }, []);

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.PREVIOUS].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isFirstItem) {
        handleNavigatePrevious();
      }
    },
    [isFirstItem, handleNavigatePrevious],
  );

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.NEXT].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isLastItem) {
        handleNavigateNext();
      }
    },
    [isLastItem, handleNavigateNext],
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
      <ConfirmDialog
        open={showUnsavedDialog}
        setOpen={setShowUnsavedDialog}
        onConfirm={handleConfirmDiscard}
        onCancel={handleCancelNavigation}
        title="Unsaved changes"
        description="You have unsaved changes. Are you sure you want to continue without saving? Your changes will be lost."
        confirmText="Discard changes"
        cancelText="Go back"
      />
      <SMEFlowLayout
        header={header}
        footer={
          <>
            <ReturnToAnnotationQueueButton />
            <div className="flex items-center gap-2">
              <div className="comet-body-s flex items-center text-light-slate">
                {currentIndex + 1} of {queueItems.length}
              </div>
              <TooltipWrapper
                content="Previous item"
                hotkeys={[SME_HOTKEYS[SME_ACTION.PREVIOUS].display]}
              >
                <Button
                  variant="outline"
                  onClick={handleNavigatePrevious}
                  disabled={isFirstItem}
                  aria-label="Go to previous item"
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
                  onClick={handleNavigateNext}
                  disabled={isLastItem}
                  aria-label="Go to next item"
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
                  aria-label={
                    isLastUnprocessedItem
                      ? "Submit and complete all annotations"
                      : "Submit and go to next item"
                  }
                >
                  {isLastUnprocessedItem
                    ? "Submit & Complete"
                    : "Submit + Next"}
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
