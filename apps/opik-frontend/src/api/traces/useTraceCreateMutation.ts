import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

// ALEX MAKE CONSTANT FOR QUERY
import api, { TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

import { snakeCaseObj } from "@/lib/utils";

type UseTraceCreateMutationParams = {
  id: string;
  projectName?: string;
  name: string;
  startTime: string;
  endTime?: string;
  // ALEX
  input?: any;
  output?: any;
  tags?: string[];
  metadata?: object;
};

const useTraceCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async (trace: UseTraceCreateMutationParams) => {
      const response = await api.post(
        TRACES_REST_ENDPOINT,
        snakeCaseObj(trace),
      );

      return response;
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
      if (variables.projectName) {
        queryClient.invalidateQueries({
          queryKey: ["projects"],
        });
      }

      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY],
      });
    },
  });
};

export default useTraceCreateMutation;
