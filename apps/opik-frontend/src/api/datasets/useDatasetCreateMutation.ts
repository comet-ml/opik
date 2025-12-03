import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError, HttpStatusCode } from "axios";
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
      const statusCode = get(error, ["response", "status"]);
      if (statusCode === HttpStatusCode.Conflict) {
        return;
      }

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

export default useDatasetCreateMutation;
