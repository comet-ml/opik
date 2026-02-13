import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import { extractErrorMessage } from "@/lib/tags";

interface DatasetItemChangesPayload {
  added_items: DatasetItem[];
  edited_items: Partial<DatasetItem>[];
  deleted_ids: string[];
  base_version: string;
  tags?: string[];
  change_description?: string;
}

interface UseDatasetItemChangesMutationParams {
  datasetId: string;
  payload: DatasetItemChangesPayload;
  override?: boolean;
}

interface UseDatasetItemChangesMutationOptions {
  onConflict?: () => void;
}

const useDatasetItemChangesMutation = (
  options?: UseDatasetItemChangesMutationOptions,
) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      payload,
      override = false,
    }: UseDatasetItemChangesMutationParams) => {
      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}${datasetId}/items/changes`,
        payload,
        {
          params: {
            override,
          },
        },
      );
      return data;
    },
    onError: (error: AxiosError) => {
      // Check for 409 Conflict
      if (error.response?.status === 409) {
        // Don't show toast, let the caller handle it
        options?.onConflict?.();
        return;
      }

      // For other errors, show toast

      toast({
        title: "Error",
        description: extractErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      if (error) {
        return;
      }
      queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId: variables.datasetId }],
      });
      queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetItemChangesMutation;
