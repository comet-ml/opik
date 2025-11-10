import React from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import InstructionsContent from "@/components/pages-shared/annotation-queues/InstructionsContent";
import ScoresContent from "@/components/pages-shared/annotation-queues/ScoresContent";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { useSMEFlow } from "../SMEFlowContext";

const GetStartedView: React.FC = () => {
  const {
    annotationQueue,
    canStartAnnotation,
    handleStartAnnotating,
    processedCount,
  } = useSMEFlow();

  if (!annotationQueue) {
    return null;
  }

  return (
    <SMEFlowLayout
      header={
        <>
          <h1 className="comet-title-xl mb-1">
            Welcome to {annotationQueue?.name ?? "opik annotation"}
          </h1>
          <div className="comet-body-s mt-2 text-muted-slate">
            You&apos;ve been invited to review examples of AI responses in this
            queue and share your feedback.
          </div>
        </>
      }
      footer={
        <>
          <ReturnToAnnotationQueueButton />
          <div className="flex gap-2">
            <Button
              onClick={handleStartAnnotating}
              disabled={!canStartAnnotation}
            >
              {processedCount > 0 ? "Resume annotating" : "Start annotating"}
            </Button>
          </div>
        </>
      }
    >
      <div className="flex flex-col gap-8">
        {!canStartAnnotation && (
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              All items in this annotation queue have already been processed and
              do not require additional annotation.
            </AlertDescription>
          </Alert>
        )}
        <div>
          <h2 className="comet-title-l mb-4">Instructions</h2>
          <InstructionsContent annotationQueue={annotationQueue} />
        </div>
        <div>
          <h2 className="comet-title-l mb-1">Feedback options</h2>
          <div className="comet-body-s mb-4 text-muted-slate">
            Here are the types of feedback you can give when reviewing
            responses, along with the possible values for each.
          </div>
          <ScoresContent annotationQueue={annotationQueue} />
        </div>
      </div>
    </SMEFlowLayout>
  );
};

export default GetStartedView;
