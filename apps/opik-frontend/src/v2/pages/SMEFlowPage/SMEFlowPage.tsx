import React from "react";
import Loader from "@/shared/Loader/Loader";
import NoDataView from "./NoDataView";
import GetStartedView from "./GetStartedView/GetStartedView";
import AnnotationView from "./AnnotationView/AnnotationView";
import CompletionView from "./CompletionView/CompletionView";
import AnnotatingHeader from "@/v2/pages/SMEFlowPage/AnnotatingHeader";
import { Button } from "@/ui/button";
import { Info } from "lucide-react";
import SMEFlowProvider, { useSMEFlow, WORKFLOW_STATUS } from "./SMEFlowContext";

const SMEFlowContent: React.FunctionComponent = () => {
  const {
    annotationQueue,
    currentView,
    setCurrentView,
    isLoading,
    isItemsLoading,
    isError,
    isItemsError,
  } = useSMEFlow();

  if (isLoading || isItemsLoading) {
    return (
      <Loader
        message="Loading annotation queue..."
        className="min-h-96 w-full"
      />
    );
  }

  if (isError) {
    return <NoDataView variant="queue-error" />;
  }

  if (!annotationQueue) {
    return <NoDataView variant="no-queue" />;
  }

  if (isItemsError) {
    return (
      <NoDataView
        variant="items-error"
        onRetry={() => window.location.reload()}
      />
    );
  }

  switch (currentView) {
    case WORKFLOW_STATUS.ANNOTATING:
      return (
        <AnnotationView
          header={
            <AnnotatingHeader
              content={
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setCurrentView(WORKFLOW_STATUS.INITIAL)}
                >
                  <Info className="mr-1.5 size-3.5 shrink-0" />
                  Read instructions
                </Button>
              }
            />
          }
        />
      );
    case WORKFLOW_STATUS.COMPLETED:
      return <CompletionView header={<AnnotatingHeader />} />;
    case WORKFLOW_STATUS.INITIAL:
    default:
      return <GetStartedView />;
  }
};

const SMEFlowPage: React.FC = () => {
  return (
    <SMEFlowProvider>
      <SMEFlowContent />
    </SMEFlowProvider>
  );
};

export default SMEFlowPage;
