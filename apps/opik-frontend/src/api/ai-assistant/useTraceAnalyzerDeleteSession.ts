import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { BASE_OPIK_AI_URL, TRACE_AI_ASSISTANT_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import get from "lodash/get";
type UseTraceAnalyzerDeleteSessionParams = {
  traceId: string;
};

const useTraceAnalyzerDeleteSession = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ traceId }: UseTraceAnalyzerDeleteSessionParams) => {
      const { data } = await api.delete(`/trace-analyzer/session/${traceId}`, {
        baseURL: BASE_OPIK_AI_URL,
      });
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
    onSettled: (_data, _error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [TRACE_AI_ASSISTANT_KEY, { traceId: variables.traceId }],
      });
    },
  });
};

export default useTraceAnalyzerDeleteSession;
