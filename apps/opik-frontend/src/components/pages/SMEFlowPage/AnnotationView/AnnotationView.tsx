import React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useHotkeys } from "react-hotkeys-hook";
import { Card } from "@/components/ui/card";
import TraceDataViewer from "./TraceDataViewer";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import CommentAndScoreViewer from "@/components/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";
import ValidationAlert from "./ValidationAlert";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useSMEFlow } from "../SMEFlowContext";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import ThreadDataViewer from "./ThreadDataViewer";

interface AnnotationViewProps {
  header: React.ReactNode;
}

const LEFT_HOTKEYS = ["←"];
const RIGHT_HOTKEYS = ["→"];
const ENTER_HOTKEYS = ["Enter"];

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  header,
}) => {
  const {
    annotationQueue,
    currentIndex,
    queueItems,
    validationState,
    isLastUnprocessedItem,
    handleNext,
    handlePrevious,
    handleSubmit,
  } = useSMEFlow();

  const isLastItem = currentIndex === queueItems.length - 1;
  const isFirstItem = currentIndex === 0;

  useHotkeys(
    "ArrowLeft",
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isFirstItem) {
        handlePrevious();
      }
    },
    [isFirstItem, handlePrevious],
  );

  useHotkeys(
    "ArrowRight",
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      if (!isLastItem) {
        handleNext();
      }
    },
    [isLastItem, handleNext],
  );

  useHotkeys(
    "Enter",
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
    <SMEFlowLayout
      header={header}
      footer={
        <>
          <ReturnToAnnotationQueueButton />
          <div className="flex items-center gap-2">
            <div className="comet-body-s flex items-center text-light-slate">
              {currentIndex + 1} of {queueItems.length}
            </div>
            <TooltipWrapper content="Previous item" hotkeys={LEFT_HOTKEYS}>
              <Button
                variant="outline"
                onClick={handlePrevious}
                disabled={isFirstItem}
              >
                <ChevronLeft className="mr-2 size-4" />
                Previous
              </Button>
            </TooltipWrapper>
            <TooltipWrapper content="Skip to next item" hotkeys={RIGHT_HOTKEYS}>
              <Button
                variant="outline"
                onClick={handleNext}
                disabled={isLastItem}
              >
                Skip
                <ChevronRight className="ml-2 size-4" />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper
              content="Submit and continue"
              hotkeys={ENTER_HOTKEYS}
            >
              <Button
                onClick={handleSubmit}
                disabled={!validationState.canSubmit}
              >
                {isLastUnprocessedItem ? "Submit & Complete" : "Submit + Next"}
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
  );
};

export default AnnotationView;
