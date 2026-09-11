import { AxiosError, HttpStatusCode } from "axios";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import { FEEDBACK_SCORE_SOURCE_MAP } from "@/lib/feedback-scores";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

export type FeedbackScoreSource = {
  isLoading: boolean;
  queueId?: string;
  label: string;
};

const useFeedbackScoreSourceLabel = (
  sourceQueueId: string | undefined,
  source: FEEDBACK_SCORE_TYPE,
): FeedbackScoreSource => {
  const {
    data: queue,
    isLoading,
    error,
  } = useAnnotationQueueById(
    { annotationQueueId: sourceQueueId ?? "" },
    { enabled: !!sourceQueueId },
  );

  if (!sourceQueueId) {
    return { isLoading: false, label: FEEDBACK_SCORE_SOURCE_MAP[source] };
  }

  if (queue?.name) {
    return { isLoading: false, queueId: sourceQueueId, label: queue.name };
  }

  if (isLoading) {
    return { isLoading: true, label: "" };
  }

  if (
    (error as AxiosError | null)?.response?.status === HttpStatusCode.NotFound
  ) {
    return { isLoading: false, label: "<deleted queue>" };
  }

  return { isLoading: false, label: "<unknown queue>" };
};

export default useFeedbackScoreSourceLabel;
