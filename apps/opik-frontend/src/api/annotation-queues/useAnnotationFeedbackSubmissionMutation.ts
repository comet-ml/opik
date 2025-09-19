import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { Trace, Thread } from "@/types/traces";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";

type UseAnnotationFeedbackSubmissionMutationParams = {
  item: Trace | Thread;
  feedbackScores: Array<{
    name: string;
    value: number;
  }>;
  comment?: string;
  annotationQueueId: string;
  projectName: string;
  scope: ANNOTATION_QUEUE_SCOPE;
};

const useAnnotationFeedbackSubmissionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      item,
      feedbackScores,
      comment,
      annotationQueueId,
      projectName,
      scope,
    }: UseAnnotationFeedbackSubmissionMutationParams) => {
      // Prepare feedback scores for submission
      const scores = feedbackScores.map((score) => ({
        name: score.name,
        value: score.value,
        source: FEEDBACK_SCORE_TYPE.ui,
        reason: comment || undefined,
      }));

      let endpoint: string;
      let payload: any;

      if (scope === ANNOTATION_QUEUE_SCOPE.TRACE) {
        // Submit to trace endpoint
        const trace = item as Trace;
        endpoint = `${TRACES_REST_ENDPOINT}${trace.id}/feedback-scores`;
        payload = {
          category_name: annotationQueueId,
          scores: scores.map((score) => ({
            ...score,
            trace_id: trace.id,
            project_name: projectName,
          })),
        };
      } else {
        // Submit to thread endpoint
        const thread = item as Thread;
        endpoint = `${TRACES_REST_ENDPOINT}threads/feedback-scores`;
        payload = {
          scores: scores.map((score) => ({
            ...score,
            thread_id: thread.id,
            project_name: projectName,
            category_name: annotationQueueId,
          })),
        };
      }

      const { data } = await api.put(endpoint, payload);
      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: () => {
      toast({
        title: "Success",
        description: "Feedback submitted successfully",
      });
    },
    onSettled: async (data, error, variables) => {
      // Invalidate relevant queries to refresh data
      await queryClient.invalidateQueries({
        queryKey: ["traces", { projectId: variables.projectName }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["threads", { projectId: variables.projectName }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["annotation-queues", variables.annotationQueueId],
      });
    },
  });
};

export default useAnnotationFeedbackSubmissionMutation;
