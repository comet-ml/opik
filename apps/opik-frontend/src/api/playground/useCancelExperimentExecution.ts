import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";
import { AxiosError } from "axios";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";

interface UseCancelExperimentExecutionParams {
  experimentIds: string[];
}

const cancelExperimentExecution = async ({
  experimentIds,
}: UseCancelExperimentExecutionParams) => {
  await api.post(`${EXPERIMENTS_REST_ENDPOINT}cancel`, { ids: experimentIds });
};

export default function useCancelExperimentExecution() {
  const { toast } = useToast();

  return useMutation({
    mutationFn: cancelExperimentExecution,
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "message"]) ||
        error.message ||
        "An unexpected error occurred while stopping the run";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
  });
}
