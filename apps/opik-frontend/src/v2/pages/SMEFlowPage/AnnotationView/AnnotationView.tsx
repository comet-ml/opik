import React, { useCallback } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import TraceDataViewer from "./TraceDataViewer";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import CommentAndScoreViewer from "@/v2/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useSMEFlow, ITEM_STATE, WORKFLOW_STATUS } from "../SMEFlowContext";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import ThreadDataViewer from "./ThreadDataViewer";
import ItemsSidebar from "./ItemsSidebar";
import { SME_ACTION, SME_HOTKEYS } from "../hotkeys";

interface AnnotationViewProps {
  header: React.ReactNode;
}

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  header,
}) => {
  const {
    annotationQueue,
    itemStates,
    nextDefaultItem,
    handleNextDefault,
    flushPendingChanges,
    setCurrentView,
  } = useSMEFlow();

  const allDone = Object.values(itemStates).every(
    (s) => s !== ITEM_STATE.DEFAULT,
  );

  const isNextDisabled = !nextDefaultItem;

  const handleNext = useCallback(() => {
    if (allDone) {
      flushPendingChanges();
      setCurrentView(WORKFLOW_STATUS.COMPLETED);
    } else if (!isNextDisabled) {
      handleNextDefault();
    }
  }, [
    allDone,
    isNextDisabled,
    flushPendingChanges,
    handleNextDefault,
    setCurrentView,
  ]);

  useHotkeys(
    SME_HOTKEYS[SME_ACTION.NEXT_DEFAULT].key,
    (keyboardEvent: KeyboardEvent) => {
      keyboardEvent.preventDefault();
      handleNext();
    },
    { enableOnFormTags: true },
    [handleNext],
  );

  const isThread = annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

  return (
    <div className="-mx-6 flex h-full flex-col overflow-hidden bg-soft-background px-16 pb-4 pt-8">
      <div className="flex shrink-0 items-center justify-between pb-4">
        {header}
      </div>
      <div className="mb-4 min-h-0 flex-1 overflow-hidden rounded-md border border-border bg-background">
        <div className="flex h-full flex-row">
          <ItemsSidebar />
          <div className="flex flex-1 flex-row overflow-hidden">
            <div className="flex flex-[2] flex-col overflow-hidden border-r border-border">
              <TraceDataViewer.Header />
              <div className="flex-1 overflow-y-auto p-3">
                {isThread ? <ThreadDataViewer /> : <TraceDataViewer.Content />}
              </div>
            </div>
            <div className="flex flex-[1] flex-col overflow-hidden">
              <div className="flex h-10 shrink-0 items-center border-b border-border bg-soft-background px-3">
                <span className="comet-body-xs-accented text-foreground">
                  Annotate
                </span>
              </div>
              <div className="flex-1 overflow-y-auto px-3 py-2">
                <CommentAndScoreViewer />
              </div>
            </div>
          </div>
        </div>
      </div>
      <div className="flex shrink-0 justify-between gap-2 border-t border-border pt-3">
        <ReturnToAnnotationQueueButton />
        {allDone ? (
          <Button onClick={handleNext}>
            Finish annotating
            <HotkeyDisplay
              hotkey={SME_HOTKEYS[SME_ACTION.NEXT_DEFAULT].display}
              size="sm"
              className="ml-2"
            />
          </Button>
        ) : (
          <TooltipWrapper
            content={SME_HOTKEYS[SME_ACTION.NEXT_DEFAULT].description}
            hotkeys={[SME_HOTKEYS[SME_ACTION.NEXT_DEFAULT].display]}
          >
            <Button onClick={handleNextDefault} disabled={isNextDisabled}>
              Next
              <HotkeyDisplay
                hotkey={SME_HOTKEYS[SME_ACTION.NEXT_DEFAULT].display}
                size="sm"
                className="ml-2"
              />
            </Button>
          </TooltipWrapper>
        )}
      </div>
    </div>
  );
};

export default AnnotationView;
