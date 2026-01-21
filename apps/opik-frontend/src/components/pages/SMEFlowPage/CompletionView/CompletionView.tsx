import React from "react";
import { Eye } from "lucide-react";
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
  const { handleReviewAnnotations } = useSMEFlow();

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
          <p className="mt-2">You can close this tab.</p>
        </div>
        <div className="mt-6">
          <Button
            variant="outline"
            onClick={handleReviewAnnotations}
            aria-label="Review annotations"
          >
            <Eye className="mr-2 size-4" />
            Review annotations
          </Button>
        </div>
      </Card>
    </SMEFlowLayout>
  );
};

export default CompletionView;
