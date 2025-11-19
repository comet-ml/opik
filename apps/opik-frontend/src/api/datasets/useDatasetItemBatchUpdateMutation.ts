import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetItemBatchUpdateMutationParams = {
  datasetId: string;
  itemIds: string[];
  item: Partial<DatasetItem>;
  mergeTags?: boolean;
};

const useDatasetItemBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      itemIds,
      item,
      mergeTags,
    }: UseDatasetItemBatchUpdateMutationParams) => {
      const { data } = await api.patch(DATASETS_REST_ENDPOINT + "items/batch", {
        ids: itemIds,
        update: item,
        merge_tags: mergeTags,
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetItemBatchUpdateMutation;
