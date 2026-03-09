import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";
import {
  TagUpdateFields,
  buildTagUpdatePayload,
  extractErrorMessage,
} from "@/lib/tags";

type UseTraceBatchUpdateMutationParams = {
  projectId: string;
  traceIds: string[];
  trace: Partial<Trace> & TagUpdateFields;
};

const useTraceBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      traceIds,
      trace,
    }: UseTraceBatchUpdateMutationParams) => {
      const { data } = await api.patch(TRACES_REST_ENDPOINT + "batch", {
        ids: traceIds,
        update: buildTagUpdatePayload(trace),
      });

      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: extractErrorMessage(error),
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
