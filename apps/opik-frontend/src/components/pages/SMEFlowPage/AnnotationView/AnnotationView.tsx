import React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Card } from "@/components/ui/card";
import InputOutputViewer from "./InputOutputViewer";
import SMEFlowLayout from "../SMEFlowLayout";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import CommentAndScoreViewer from "@/components/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";
import { useSMEFlow } from "../SMEFlowContext";

interface AnnotationViewProps {
  header: React.ReactNode;
}

// TODO lala add hotkeys for next/prev

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  header,
}) => {
  const { currentIndex, queueItems, handleNext, handlePrevious, handleSubmit } =
    useSMEFlow();

  const isLastItem = currentIndex === queueItems.length - 1;
  const isFirstItem = currentIndex === 0;

  return (
    <SMEFlowLayout
      header={header}
      footer={
        <>
          <Button
            variant="outline"
            onClick={handlePrevious}
            disabled={isFirstItem}
          >
            <ChevronLeft className="mr-2 size-4" />
            Previous
          </Button>
          <Button variant="outline" onClick={handleNext}>
            Skip
          </Button>
          <Button onClick={handleSubmit}>
            {isLastItem ? "Submit & Complete" : "Submit + Next"}
            <ChevronRight className="ml-2 size-4" />
          </Button>
        </>
      }
    >
      <Card className="flex h-full flex-row items-stretch p-4">
        <div className="flex-[2] overflow-y-auto">
          <InputOutputViewer />
        </div>
        <Separator orientation="vertical" className="mx-3" />
        <div className="flex-[1] overflow-y-auto">
          <CommentAndScoreViewer />
        </div>
      </Card>
    </SMEFlowLayout>
  );
};

export default AnnotationView;
