import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import Loader from "@/components/shared/Loader/Loader";
import NoDataView from "./NoDataView";
import GetStartedView from "./GetStartedView";
import AnnotationView from "./AnnotationView";
import CompletionView from "./CompletionView";
import {
  useAnnotationWorkflow,
  WORKFLOW_STATUS,
} from "./hooks/useAnnotationWorkflow";

const SMEFlowPage: React.FunctionComponent = () => {
  const [status = WORKFLOW_STATUS.INITIAL] = useQueryParam(
    "status",
    StringParam,
    {
      updateType: "replaceIn",
    },
  ) as [WORKFLOW_STATUS, (status: WORKFLOW_STATUS) => void];

  const workflow = useAnnotationWorkflow();

  if (workflow.isLoading || workflow.isItemsLoading) {
    return (
      <Loader
        message="Loading annotation queue..."
        className="min-h-96 w-full"
      />
    );
  }

  if (!workflow.annotationQueue || workflow.isError) {
    return <NoDataView hasQueueId={!!workflow.annotationQueue} />;
  }

  const renderContent = () => {
    switch (status) {
      case WORKFLOW_STATUS.ANNOTATING:
        return workflow.annotationQueue ? (
          <AnnotationView
            annotationQueue={workflow.annotationQueue}
            unprocessedItems={workflow.unprocessedItems}
            processedCount={workflow.processedCount}
            currentItem={workflow.currentItem}
            currentIndex={workflow.currentIndex}
            onPrevious={workflow.handlePrevious}
            onSkip={workflow.handleSkip}
            onSubmitNext={workflow.handleSubmitNext}
          />
        ) : null;
      case WORKFLOW_STATUS.COMPLETED:
        return workflow.annotationQueue ? (
          <CompletionView
            annotationQueue={workflow.annotationQueue}
            queueItems={workflow.queueItems}
            processedCount={workflow.processedCount}
          />
        ) : null;
      case WORKFLOW_STATUS.INITIAL:
      default:
        return workflow.annotationQueue ? (
          <GetStartedView
            annotationQueue={workflow.annotationQueue}
            canStartAnnotation={workflow.canStartAnnotation}
            onStartAnnotating={workflow.handleStartAnnotating}
          />
        ) : null;
    }
  };

  return renderContent();
};

export default SMEFlowPage;
