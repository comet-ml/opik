import { useParams } from "@tanstack/react-router";

export const useAnnotationQueueIdFromURL = () => {
  return useParams({
    select: (params) => params["annotationQueueId"],
    from: "/workspaceGuard/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId",
  });
};
