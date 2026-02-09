import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { Experiment } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseExperimentBatchUpdateMutationParams = {
  ids: string[];
  experiment: Partial<Experiment> & {
    tagsToAdd?: string[];
    tagsToRemove?: string[];
  };
};

const useExperimentBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      experiment,
    }: UseExperimentBatchUpdateMutationParams) => {
      const { tagsToAdd, tagsToRemove, ...rest } = experiment;

      const payload: Record<string, unknown> = { ...rest };
      if (tagsToAdd !== undefined) payload.tags_to_add = tagsToAdd;
      if (tagsToRemove !== undefined) payload.tags_to_remove = tagsToRemove;

      const { data } = await api.patch(`${EXPERIMENTS_REST_ENDPOINT}batch`, {
        ids,
        update: payload,
      });
      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message ??
          "An unknown error occurred while updating experiments. Please try again later.",
      );

      toast({
        title: "Error",
        description: message,
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
