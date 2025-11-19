import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";

type UseExperimentUpdate = {
    id: string,
    name: string,
    metadata:object
};

const useExperimentUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ id, name, metadata }: UseExperimentUpdate) => {
      const { data } = await api.patch(`${EXPERIMENTS_REST_ENDPOINT}${id}`, {
        name,
        metadata
      });
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
      queryClient.invalidateQueries({ queryKey: ["datasets"] });
      return queryClient.invalidateQueries({
        queryKey: ["experiments"],
      });
    },
  });
};

export default useExperimentUpdateMutation;
