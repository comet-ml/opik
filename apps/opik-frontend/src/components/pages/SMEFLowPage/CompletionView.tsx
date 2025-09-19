import React from "react";
import { Card } from "@/components/ui/card";
import { CheckCircle, FileText } from "lucide-react";
import SMEFlowLayout from "./SMEFlowLayout";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Trace, Thread } from "@/types/traces";

interface CompletionViewProps {
  annotationQueue: AnnotationQueue;
  queueItems: (Trace | Thread)[];
  processedCount: number;
}

const CompletionView: React.FunctionComponent<CompletionViewProps> = ({
  annotationQueue,
  queueItems,
  processedCount,
}) => {
  const totalItemCount = queueItems.length;

  const header = (
    <div className="text-center">
      <CheckCircle className="mx-auto mb-4 size-16 text-green-600" />
      <h1 className="comet-title-xl mb-2">Annotation Completed</h1>
      <p className="comet-body-s text-muted-slate">
        Great job! Your annotation work is complete.
      </p>
    </div>
  );

  const children = (
    <div>
      <Card className="p-6 text-center">
        <h3 className="comet-title-m mb-4">Session Summary</h3>
        <p className="comet-body-s mb-6 text-muted-slate">
          You have successfully completed the annotation for this queue. Your
          feedback has been saved and will help improve the system.
        </p>

        <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="flex items-center justify-center gap-2">
            <FileText className="size-4 text-muted-slate" />
            <div className="text-center">
              <div className="comet-body-xs text-muted-slate">
                Items Processed
              </div>
              <div className="comet-body-s font-medium">{processedCount}</div>
            </div>
          </div>
          <div className="flex items-center justify-center gap-2">
            <FileText className="size-4 text-muted-slate" />
            <div className="text-center">
              <div className="comet-body-xs text-muted-slate">Total Items</div>
              <div className="comet-body-s font-medium">{totalItemCount}</div>
            </div>
          </div>
        </div>
      </Card>
    </div>
  );

  return <SMEFlowLayout header={header}>{children}</SMEFlowLayout>;
};

export default CompletionView;
