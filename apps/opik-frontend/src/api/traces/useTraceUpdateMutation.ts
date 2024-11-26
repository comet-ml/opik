import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { TRACE_KEY, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { useToast } from "@/components/ui/use-toast";

type UseTraceUpdateMutationParams = {
  projectId: string;
  traceId: string;
  trace: Partial<Trace>;
};

const useTraceUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ trace, traceId }: UseTraceUpdateMutationParams) => {
      const { data } = await api.patch(TRACES_REST_ENDPOINT + traceId, trace);

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
      queryClient.invalidateQueries({
        queryKey: [TRACE_KEY, { traceId: variables.traceId }],
      });
    },
  });
};

export default useTraceUpdateMutation;
