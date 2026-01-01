import React from "react";
import { ArrowUpRight } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { createFilter } from "@/lib/filters";

interface ViewAllItemsButtonProps {
  annotationQueue: AnnotationQueue;
}

const ViewAllItemsButton: React.FunctionComponent<ViewAllItemsButtonProps> = ({
  annotationQueue,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isTraceQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE;

  const handleClick = () => {
    const filter = createFilter({
      id: "annotation_queue_ids",
      field: "annotation_queue_ids",
      value: annotationQueue.id,
      operator: "contains",
    });

    if (isTraceQueue) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          workspaceName,
          projectId: annotationQueue.project_id,
        },
        search: {
          traces_filters: [filter],
        },
      });
    } else {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          workspaceName,
          projectId: annotationQueue.project_id,
        },
        search: {
          type: "threads",
          threads_filters: [filter],
        },
      });
    }
  };

  const tooltipContent = isTraceQueue
    ? "View all traces in this queue"
    : "View all threads in this queue";

  const buttonLabel = isTraceQueue ? "View all traces" : "View all threads";

  return (
    <TooltipWrapper content={tooltipContent}>
      <Button size="sm" variant="outline" onClick={handleClick}>
        {buttonLabel}
        <ArrowUpRight className="ml-1.5 size-3.5" />
      </Button>
    </TooltipWrapper>
  );
};

export default ViewAllItemsButton;
