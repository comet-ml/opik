import React, { useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { ArrowUpRight, ChevronDown } from "lucide-react";

import { AnnotationQueueReference } from "@/types/traces";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";

type AnnotationQueuesNavigationProps = {
  queues?: AnnotationQueueReference[];
};

const AnnotationQueuesNavigation: React.FC<AnnotationQueuesNavigationProps> = ({
  queues,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const sortedQueues = useMemo(
    () => [...(queues ?? [])].sort((a, b) => a.name.localeCompare(b.name)),
    [queues],
  );

  if (sortedQueues.length === 0 || !activeProjectId) {
    return null;
  }

  const goToQueue = (queue: AnnotationQueueReference) =>
    navigate({
      to: "/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId",
      params: {
        workspaceName,
        projectId: activeProjectId,
        annotationQueueId: queue.id,
      },
    });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="2xs">
          Queues ({sortedQueues.length})
          <ChevronDown className="ml-1 size-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        {sortedQueues.map((queue) => (
          <DropdownMenuItem key={queue.id} onClick={() => goToQueue(queue)}>
            <span className="truncate">{queue.name}</span>
            <ArrowUpRight className="ml-auto size-3.5 shrink-0" />
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AnnotationQueuesNavigation;
