import React from "react";
import { ArrowRight } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { useActiveWorkspaceName } from "@/store/AppStore";

interface ViewQueueItemsButtonProps {
  annotationQueue: AnnotationQueue;
}

const ViewQueueItemsButton: React.FunctionComponent<
  ViewQueueItemsButtonProps
> = ({ annotationQueue }) => {
  const navigate = useNavigate();
  const workspaceName = useActiveWorkspaceName();

  const isTraceQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE;
  const itemType = isTraceQueue ? "traces" : "threads";

  const handleClick = () => {
    navigate({
      to: "/$workspaceName/projects/$projectId/traces",
      params: {
        workspaceName,
        projectId: annotationQueue.project_id,
      },
      search: {
        type: itemType,
      },
    });
  };

  return (
    <Button variant="outline" size="sm" onClick={handleClick}>
      <ArrowRight className="mr-2 size-4" />
      View all {itemType}
    </Button>
  );
};

ViewQueueItemsButton.displayName = "ViewQueueItemsButton";

export default ViewQueueItemsButton;
