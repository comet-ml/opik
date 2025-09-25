import React from "react";
import { AlertCircle, FileText } from "lucide-react";
import { Card } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import SMEFlowLayout from "./SMEFlowLayout";

interface NoDataViewProps {
  hasQueueId: boolean;
}

const NoDataView: React.FunctionComponent<NoDataViewProps> = ({
  hasQueueId,
}) => {
  return (
    <SMEFlowLayout header={<h1 className="comet-title-xl">Error</h1>}>
      {hasQueueId ? (
        <div className="space-y-6">
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              Unable to load annotation queue. The queue may not exist or you
              may not have access to it.
            </AlertDescription>
          </Alert>

          <Card className="p-8 text-center">
            <FileText className="mx-auto mb-4 size-12 text-muted-slate" />
            <h3 className="comet-title-m mb-2">Queue not available</h3>
            <p className="comet-body-s mb-6 text-muted-slate">
              Please check the queue link or contact your administrator for
              access.
            </p>
          </Card>
        </div>
      ) : (
        <Card className="p-8 text-center">
          <FileText className="mx-auto mb-4 size-12 text-muted-slate" />
          <h3 className="comet-title-m mb-2">No annotation queue selected</h3>
          <p className="comet-body-s mb-6 text-muted-slate">
            To begin SME evaluation, please access this page through an
            annotation queue link.
          </p>
        </Card>
      )}
    </SMEFlowLayout>
  );
};

export default NoDataView;
