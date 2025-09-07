import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { AnnotationQueue } from "@/types/annotation-queues";
import api, {
  ANNOTATION_QUEUE_KEY,
  ANNOTATION_QUEUES_REST_ENDPOINT,
} from "@/api/api";
import { QueryConfig } from "@/api/api";

type UseAnnotationQueueByIdParams = {
  workspaceName: string;
  annotationQueueId: string;
};

const getAnnotationQueueById = async (
  { signal }: QueryFunctionContext,
  { workspaceName, annotationQueueId }: UseAnnotationQueueByIdParams,
) => {
  const { data } = await api.get(
    `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
      },
    },
  );

  return data;
};

export default function useAnnotationQueueById(
  params: UseAnnotationQueueByIdParams,
  options?: QueryConfig<AnnotationQueue>,
) {
  return useQuery({
    queryKey: [ANNOTATION_QUEUE_KEY, params],
    queryFn: (context) => getAnnotationQueueById(context, params),
    ...options,
  });
}
