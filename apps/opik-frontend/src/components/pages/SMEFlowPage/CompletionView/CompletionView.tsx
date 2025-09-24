import React from "react";
import { Card } from "@/components/ui/card";
import SMEFlowLayout from "../SMEFlowLayout";

interface CompletionViewProps {
  header: React.ReactNode;
}

const CompletionView: React.FunctionComponent<CompletionViewProps> = ({
  header,
}) => {
  return (
    <SMEFlowLayout header={header}>
      <Card className="p-8 text-center">
        <h3 className="comet-title-l mb-6">All Items Completed!</h3>
        <div className="space-y-4 text-center">
          <p className="comet-body text-gray-600">
            Congratulations! You have successfully completed the annotation for
            this entire queue.
          </p>
          <p className="comet-body text-gray-600">
            Your valuable feedback has been saved and will contribute to
            improving the system&apos;s performance and accuracy.
          </p>
          <p className="comet-body text-gray-600">
            Thank you for your time and expertise in helping to enhance our AI
            models through your thoughtful annotations.
          </p>
        </div>
      </Card>
    </SMEFlowLayout>
  );
};

export default CompletionView;
