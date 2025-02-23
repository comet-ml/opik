import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { TRACE_KEY, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseCreateTraceCommentMutationParams = {
  traceId: string;
  text: string;
};

const useCreateTraceCommentMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      text,
      traceId,
    }: UseCreateTraceCommentMutationParams) => {
      const { data } = await api.post(
        `${TRACES_REST_ENDPOINT}${traceId}/comments`,
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
        queryKey: [TRACE_KEY, { traceId: variables.traceId }],
      });
      queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
    },
  });
};

export default useCreateTraceCommentMutation;
