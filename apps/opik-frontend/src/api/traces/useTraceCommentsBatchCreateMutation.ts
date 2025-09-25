import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  TRACE_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseTraceCommentsBatchCreateParams = {
  ids: string[];
  text: string;
};

const useTraceCommentsBatchCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids, text }: UseTraceCommentsBatchCreateParams) => {
      const { data } = await api.post(
        `${TRACES_REST_ENDPOINT}comments/batch`,
        {
          ids,
          text,
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
      variables.ids.forEach((traceId) => {
        queryClient.invalidateQueries({
          queryKey: [TRACE_KEY, { traceId }],
        });
      });
      queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
      queryClient.invalidateQueries({
        queryKey: [COMPARE_EXPERIMENTS_KEY],
      });
    },
  });
};

export default useTraceCommentsBatchCreateMutation;

