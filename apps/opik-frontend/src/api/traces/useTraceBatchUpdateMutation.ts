import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";

type UseTraceBatchUpdateMutationParams = {
  projectId: string;
  traceIds: string[];
  trace: Partial<Trace>;
  mergeTags?: boolean;
};

const useTraceBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      traceIds,
      trace,
      mergeTags,
    }: UseTraceBatchUpdateMutationParams) => {
      const { data } = await api.patch(TRACES_REST_ENDPOINT + "batch", {
        ids: traceIds,
        update: trace,
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
        queryKey: [TRACES_KEY, { projectId: variables.projectId }],
      });
    },
  });
};

export default useTraceBatchUpdateMutation;
