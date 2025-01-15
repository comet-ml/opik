import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetUpdateMutationParams = {
  dataset: Partial<Dataset>;
};

const useDatasetUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dataset }: UseDatasetUpdateMutationParams) => {
      const { data } = await api.put(DATASETS_REST_ENDPOINT + dataset.id, {
        ...dataset,
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetUpdateMutation;
