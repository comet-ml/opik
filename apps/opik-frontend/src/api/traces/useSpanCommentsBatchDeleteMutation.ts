import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";

type UseSpanCommentsBatchDeleteMutationParams = {
  ids: string[];
  projectId: string;
};

const useSpanCommentsBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseSpanCommentsBatchDeleteMutationParams) => {
      const { data } = await api.post(`${SPANS_REST_ENDPOINT}comments/delete`, {
        ids: ids,
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [
          SPANS_KEY,
          {
            projectId: variables.projectId,
          },
        ],
      });
    },
  });
};

export default useSpanCommentsBatchDeleteMutation;
