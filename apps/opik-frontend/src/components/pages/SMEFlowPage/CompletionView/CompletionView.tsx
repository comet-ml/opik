import React from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { useSMEFlow } from "../SMEFlowContext";

interface CompletionViewProps {
  header: React.ReactNode;
}

const CompletionView: React.FunctionComponent<CompletionViewProps> = ({
  header,
}) => {
  const { handleStartReviewing } = useSMEFlow();

  return (
    <SMEFlowLayout header={header} footer={<ReturnToAnnotationQueueButton />}>
      <Card className="h-full p-6 pt-14 text-center">
        <div className="mb-5 h-8 text-[32px]">🎉</div>
        <h3 className="comet-title-l">All items completed!</h3>
        <div className="comet-body-s mt-3 text-center text-muted-slate">
          <p>
            All annotations in this queue are complete. Your feedback has been
            saved and will help improve our AI models.
          </p>
          <p>You can close this tab.</p>
        </div>
        <Button
          variant="link"
          onClick={handleStartReviewing}
          className="mt-4"
          aria-label="Review my annotations"
        >
          review my annotations
        </Button>
      </Card>
    </SMEFlowLayout>
  );
};

export default CompletionView;
