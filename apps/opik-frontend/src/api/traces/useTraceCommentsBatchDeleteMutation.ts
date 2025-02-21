import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { TRACE_KEY, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";

type UseTraceBatchDeleteMutationParams = {
  ids: string[];
  traceId: string;
};

const useTraceCommentsBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseTraceBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${TRACES_REST_ENDPOINT}/comments/delete`,
        {
          ids: ids,
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [TRACE_KEY, { traceId: variables.traceId }],
      });
      queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
    },
  });
};

export default useTraceCommentsBatchDeleteMutation;
