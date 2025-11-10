import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUE_KEY,
  QueryConfig,
} from "@/api/api";
import { AnnotationQueue } from "@/types/annotation-queues";

const getAnnotationQueueById = async (
  { signal }: QueryFunctionContext,
  { annotationQueueId }: UseAnnotationQueueByIdParams,
) => {
  const { data } = await api.get<AnnotationQueue>(
    ANNOTATION_QUEUES_REST_ENDPOINT + annotationQueueId,
    {
      signal,
    },
  );

  return data;
};

type UseAnnotationQueueByIdParams = {
  annotationQueueId: string;
};

export default function useAnnotationQueueById(
  params: UseAnnotationQueueByIdParams,
  options?: QueryConfig<AnnotationQueue>,
) {
  return useQuery({
    queryKey: [
      ANNOTATION_QUEUE_KEY,
      { annotationQueueId: params.annotationQueueId },
    ],
    queryFn: (context) => getAnnotationQueueById(context, params),
    ...options,
  });
}
