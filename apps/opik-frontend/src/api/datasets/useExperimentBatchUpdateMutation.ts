import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { Experiment } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import { TagUpdateFields, buildTagUpdatePayload } from "@/lib/tags";

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
      const message =
        get(error, ["response", "data", "errors", "0"]) ??
        get(error, ["response", "data", "message"]) ??
        error.message ??
        "An unknown error occurred while updating experiments. Please try again later.";

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
