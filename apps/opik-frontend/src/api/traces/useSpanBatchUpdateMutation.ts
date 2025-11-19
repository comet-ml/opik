import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { Span } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";

type UseSpanBatchUpdateMutationParams = {
  projectId: string;
  spanIds: string[];
  span: Partial<Span>;
  mergeTags?: boolean;
};

const useSpanBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      spanIds,
      span,
      mergeTags,
    }: UseSpanBatchUpdateMutationParams) => {
      const { data } = await api.patch(SPANS_REST_ENDPOINT + "batch", {
        ids: spanIds,
        update: span,
        merge_tags: mergeTags,
      });

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

export default useSpanBatchUpdateMutation;
