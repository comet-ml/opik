import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";

import api, { BASE_OPIK_AI_URL, TRACE_ANALYZER_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseTraceAnalyzerFeedbackDeleteMutationParams = {
  traceId: string;
};

const useTraceAnalyzerFeedbackDeleteMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      traceId,
    }: UseTraceAnalyzerFeedbackDeleteMutationParams) => {
      const { data } = await api.delete(
        `${TRACE_ANALYZER_REST_ENDPOINT}${traceId}/feedback`,
        {
          baseURL: BASE_OPIK_AI_URL,
        },
      );

      return data;
    },
    onError: (error) => {
      const message =
        get(error, ["response", "data", "message"], error.message) ||
        "Failed to remove feedback. Please try again.";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
  });
};

export default useTraceAnalyzerFeedbackDeleteMutation;
