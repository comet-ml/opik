import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { COLUMN_TYPE } from "@/types/shared";
import { createFilter } from "@/lib/filters";
import useAppStore from "@/store/AppStore";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

interface ViewAllItemsButtonProps {
  annotationQueue: AnnotationQueue;
}

const ViewAllItemsButton: React.FC<ViewAllItemsButtonProps> = ({
  annotationQueue,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isTraceQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE;
  const isThreadQueue = annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

  const handleClick = () => {
    const filter = createFilter({
      id: "annotation_queue_ids",
      field: "annotation_queue_ids",
      type: COLUMN_TYPE.list,
      operator: "contains",
      value: annotationQueue.id,
    });

    if (isTraceQueue) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: annotationQueue.project_id,
          workspaceName,
        },
        search: {
          type: TRACE_DATA_TYPE.traces,
          traces_filters: [filter],
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
          threads_filters: [filter],
        },
      });
    }
  };

  const buttonText = isTraceQueue ? "View all traces" : "View all threads";

  return (
    <Button variant="outline" size="sm" onClick={handleClick}>
      {buttonText}
      <ArrowRight className="ml-2 size-4" />
    </Button>
  );
};

export default ViewAllItemsButton;
