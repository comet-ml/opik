import React from "react";
import { ArrowRight, ListTree, MessagesSquare } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";

interface ViewQueueItemsButtonProps {
  annotationQueue: AnnotationQueue;
}

const ViewQueueItemsButton: React.FunctionComponent<
  ViewQueueItemsButtonProps
> = ({ annotationQueue }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isTraceQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE;
  const isThreadQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

  const handleClick = () => {
    if (isTraceQueue) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: annotationQueue.project_id,
          workspaceName,
        },
        search: {
          traces_filters: generateAnnotationQueueIdFilter(annotationQueue.id),
        },
      });
    } else if (isThreadQueue) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: annotationQueue.project_id,
          workspaceName,
        },
        search: {
          type: "threads",
          threads_filters: generateAnnotationQueueIdFilter(annotationQueue.id),
        },
      });
    }
  };

  const tooltipContent = isTraceQueue
    ? "View all traces in this annotation queue"
    : "View all threads in this annotation queue";

  const buttonText = isTraceQueue ? "View traces" : "View threads";

  const Icon = isTraceQueue ? ListTree : MessagesSquare;

  return (
    <TooltipWrapper content={tooltipContent}>
      <Button size="sm" variant="outline" onClick={handleClick}>
        <Icon className="mr-1.5 size-3.5" />
        {buttonText}
        <ArrowRight className="ml-1.5 size-3.5" />
      </Button>
    </TooltipWrapper>
  );
};

export default ViewQueueItemsButton;
