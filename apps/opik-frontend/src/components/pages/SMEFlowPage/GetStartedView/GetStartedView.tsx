import React from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import InstructionsContent from "@/components/pages-shared/annotation-queues/InstructionsContent";
import ScoresContent from "@/components/pages-shared/annotation-queues/ScoresContent";
import SMEFlowLayout from "../SMEFlowLayout";
import { useSMEFlow } from "../SMEFlowContext";

const GetStartedView: React.FC = () => {
  const { annotationQueue, canStartAnnotation, handleStartAnnotating } =
    useSMEFlow();

  if (!annotationQueue) {
    return null;
  }

  return (
    <SMEFlowLayout
      header={
        <>
          <h1 className="comet-title-xl">Get started with Opik</h1>
          <div className="comet-body-s text-muted-slate">
            Welcome! You&apos;ve been invited to review examples of AI responses
            in this queue and share your feedback.
          </div>
        </>
      }
      footer={
        <Button
          size="lg"
          className="px-8"
          onClick={handleStartAnnotating}
          disabled={!canStartAnnotation}
        >
          Start annotating
        </Button>
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
          <h2 className="comet-title-s mb-3">Instructions</h2>
          <InstructionsContent annotationQueue={annotationQueue} />
        </div>
        <div>
          <h2 className="comet-title-s mb-3">Feedback options</h2>
          <div className="comet-body-s mb-6 text-muted-slate">
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
