import React from "react";
import { Card } from "@/components/ui/card";
import { FileText } from "lucide-react";
import SMEFlowLayout from "../SMEFlowLayout";
import { useSMEFlow } from "../SMEFlowContext";

interface CompletionViewProps {
  header: React.ReactNode;
}

const CompletionView: React.FunctionComponent<CompletionViewProps> = ({
  header,
}) => {
  const { queueItems, processedCount } = useSMEFlow();
  const totalItemCount = queueItems.length;

  return (
    <SMEFlowLayout header={header}>
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
    </SMEFlowLayout>
  );
};

export default CompletionView;
