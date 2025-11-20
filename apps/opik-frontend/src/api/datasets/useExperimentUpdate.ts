import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { Experiment } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseExperimentUpdateMutationParams = {
  experiment: Partial<Experiment> & { id: string };
};

const useExperimentUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ experiment }: UseExperimentUpdateMutationParams) => {
      const { data } = await api.patch(
        EXPERIMENTS_REST_ENDPOINT + experiment.id,
        experiment,
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
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["experiment", { experimentId: variables.experiment.id }],
      });
      queryClient.invalidateQueries({
        queryKey: ["experiments"],
      });
    },
  });
};

export default useExperimentUpdateMutation;
