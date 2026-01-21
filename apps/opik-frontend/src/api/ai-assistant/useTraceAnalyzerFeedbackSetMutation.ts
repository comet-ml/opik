import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";

import api, { BASE_OPIK_AI_URL, TRACE_ANALYZER_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { SESSION_FEEDBACK_VALUE } from "@/types/ai-assistant";

type UseTraceAnalyzerFeedbackSetMutationParams = {
  traceId: string;
  value: SESSION_FEEDBACK_VALUE;
};

const useTraceAnalyzerFeedbackSetMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      traceId,
      value,
    }: UseTraceAnalyzerFeedbackSetMutationParams) => {
      const { data } = await api.put(
        `${TRACE_ANALYZER_REST_ENDPOINT}${traceId}/feedback`,
        {
          value,
        },
        {
          baseURL: BASE_OPIK_AI_URL,
        },
      );

      return data;
    },
    onError: (error) => {
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
  });
};

export default useTraceAnalyzerFeedbackSetMutation;
