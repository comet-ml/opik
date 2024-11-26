import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { Span } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";

type UseSpanUpdateMutationParams = {
  projectId: string;
  spanId: string;
  span: Partial<Span>;
};

const useSpanUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ span, spanId }: UseSpanUpdateMutationParams) => {
      const { data } = await api.patch(SPANS_REST_ENDPOINT + spanId, span);

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

export default useSpanUpdateMutation;
