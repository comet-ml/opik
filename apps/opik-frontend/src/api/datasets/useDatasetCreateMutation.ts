import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseDatasetCreateMutationParams = {
  dataset: Partial<Dataset>;
};

const useDatasetCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dataset }: UseDatasetCreateMutationParams) => {
      const { data, headers } = await api.post(DATASETS_REST_ENDPOINT, {
        ...dataset,
      });

      if (data) {
        return data;
      }

      const extractedId = extractIdFromLocation(headers?.location);

      if (!extractedId) {
        throw new Error("Failed to create dataset: No ID returned from server");
      }

      return {
        ...dataset,
        id: extractedId,
      };
    },
    onError: (error: AxiosError) => {
      // Backend returns {errors: ["error message"]} for 409 conflicts
      const errors = get(error, ["response", "data", "errors"], []);
      const message =
        Array.isArray(errors) && errors.length > 0
          ? errors[0]
          : get(error, ["response", "data", "message"], error.message);

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

export default useDatasetCreateMutation;
