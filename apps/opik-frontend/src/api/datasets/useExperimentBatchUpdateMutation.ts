import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { Experiment } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import {
  TagUpdateFields,
  buildTagUpdatePayload,
  extractErrorMessage,
} from "@/lib/tags";

type UseExperimentBatchUpdateMutationParams = {
  ids: string[];
  experiment: Partial<Experiment> & TagUpdateFields;
};

const useExperimentBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      experiment,
    }: UseExperimentBatchUpdateMutationParams) => {
      const { data } = await api.patch(`${EXPERIMENTS_REST_ENDPOINT}batch`, {
        ids,
        update: buildTagUpdatePayload(experiment),
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
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ["experiments"],
      });
      queryClient.invalidateQueries({
        queryKey: ["experiment"],
      });
    },
  });
};

export default useExperimentBatchUpdateMutation;
