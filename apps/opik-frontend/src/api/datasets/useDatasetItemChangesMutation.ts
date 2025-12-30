import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

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
    onSuccess: () => {
      toast({
        title: "Changes saved",
        description: "Dataset version created successfully.",
      });
    },
    onError: (error: AxiosError) => {
      // Check for 409 Conflict
      if (error.response?.status === 409) {
        // Don't show toast, let the caller handle it
        options?.onConflict?.();
        return;
      }

      // For other errors, show toast
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
