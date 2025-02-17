import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseCreateSpanCommentMutationParams = {
  spanId: string;
  projectId: string;
  text: string;
};

const useCreateSpanCommentMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      text,
      spanId,
    }: UseCreateSpanCommentMutationParams) => {
      const { data } = await api.post(
        `${SPANS_REST_ENDPOINT}${spanId}/comments`,
        { text },
      );

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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [SPANS_KEY, { projectId: variables.projectId }],
      });
    },
  });
};

export default useCreateSpanCommentMutation;
