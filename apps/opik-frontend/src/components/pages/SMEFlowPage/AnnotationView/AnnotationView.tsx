import React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Card } from "@/components/ui/card";
import { Trace, Thread } from "@/types/traces";
import InputOutputViewer from "./InputOutputViewer";
import SMEFlowLayout from "../SMEFlowLayout";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import CommentAndScoreViewer from "@/components/pages/SMEFlowPage/AnnotationView/CommentAndScoreViewer";

interface AnnotationViewProps {
  header: React.ReactNode;
  currentItem?: Trace | Thread;
}

// TODO lala add hotkeys for next/prev

const AnnotationView: React.FunctionComponent<AnnotationViewProps> = ({
  header,
  currentItem,
}) => {
  const isLastItem = false;

  return (
    <SMEFlowLayout
      header={header}
      footer={
        <>
          <Button variant="outline">
            <ChevronLeft className="mr-2 size-4" />
            Previous
          </Button>
          <Button variant="outline">Skip</Button>
          <Button>
            {isLastItem ? "Submit & Complete" : "Submit + Next"}
            <ChevronRight className="ml-2 size-4" />
          </Button>
        </>
      }
    >
      <Card className="flex h-full flex-row items-stretch p-4">
        <div className="flex-[2] overflow-y-auto">
          <InputOutputViewer item={currentItem} />
        </div>
        <Separator orientation="vertical" className="mx-3" />
        <div className="flex-[1]">
          <CommentAndScoreViewer item={currentItem} />
        </div>
      </Card>
    </SMEFlowLayout>
  );
};

export default AnnotationView;
