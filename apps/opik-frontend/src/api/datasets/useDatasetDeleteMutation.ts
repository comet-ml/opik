import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseDatasetDeleteMutationParams = {
  datasetId: string;
};

const useDatasetDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ datasetId }: UseDatasetDeleteMutationParams) => {
      const { data } = await api.delete(DATASETS_REST_ENDPOINT + datasetId);
      return data;
    },
    onError: (error) => {
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
      return queryClient.invalidateQueries({ queryKey: ["datasets"] });
    },
  });
};

export default useDatasetDeleteMutation;
