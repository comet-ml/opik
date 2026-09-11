import React from "react";
import { ArrowLeft } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/ui/button";
import useAppStore from "@/store/AppStore";
import { useSMEFlow } from "./SMEFlowContext";

const ReturnToAnnotationQueueButton: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { annotationQueue, flushPendingChanges } = useSMEFlow();

  const projectId = annotationQueue?.project_id || "";
  const queueId = annotationQueue?.id || "";

  return (
    <Link
      to="/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId"
      params={{
        workspaceName,
        projectId,
        annotationQueueId: queueId,
      }}
      onClick={flushPendingChanges}
    >
      <Button variant="ghost" aria-label="Return to annotation queue">
        <ArrowLeft className="mr-2 size-4" />
        Return to annotation queue
      </Button>
    </Link>
  );
};

export default ReturnToAnnotationQueueButton;
