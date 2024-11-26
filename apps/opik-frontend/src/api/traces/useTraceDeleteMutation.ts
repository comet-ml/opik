import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { UseTracesListResponse } from "@/api/traces/useTracesList";

type UseTraceDeleteMutationParams = {
  traceId: string;
  projectId: string;
};

const useTraceDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ traceId }: UseTraceDeleteMutationParams) => {
      const { data } = await api.delete(TRACES_REST_ENDPOINT + traceId);
      return data;
    },
    onMutate: async (params: UseTraceDeleteMutationParams) => {
      const queryKey = [TRACES_KEY, { projectId: params.projectId }];

      await queryClient.cancelQueries({ queryKey });
      const previousTraces: UseTracesListResponse | undefined =
        queryClient.getQueryData(queryKey);
      if (previousTraces) {
        queryClient.setQueryData(queryKey, () => {
          return {
            ...previousTraces,
            content: previousTraces.content.filter(
              (p) => p.id !== params.traceId,
            ),
          };
        });
      }

      return { previousTraces, queryKey };
    },
    onError: (error, data, context) => {
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

      if (context) {
        queryClient.setQueryData(context.queryKey, context.previousTraces);
      }
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        queryClient.invalidateQueries({
          queryKey: [SPANS_KEY, { projectId: variables.projectId }],
        });
        queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useTraceDeleteMutation;
