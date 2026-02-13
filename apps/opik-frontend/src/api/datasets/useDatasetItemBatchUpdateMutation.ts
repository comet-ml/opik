import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import { Filters } from "@/types/filters";
import {
  generateSearchByFieldFilters,
  processFiltersArray,
} from "@/lib/filters";
import { TagUpdateFields, buildTagUpdatePayload } from "@/lib/tags";

type UseDatasetItemBatchUpdateMutationParams = {
  datasetId: string;
  itemIds: string[];
  item: Partial<DatasetItem> & TagUpdateFields;
  isAllItemsSelected?: boolean;
  filters?: Filters;
  search?: string;
  batchGroupId?: string;
};

const useDatasetItemBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      itemIds,
      item,
      isAllItemsSelected,
      filters = [],
      search,
      batchGroupId,
    }: UseDatasetItemBatchUpdateMutationParams) => {
      const updatePayload = buildTagUpdatePayload(item);

      let payload;

      if (isAllItemsSelected) {
        const combinedFilters = [
          ...filters,
          ...generateSearchByFieldFilters("full_data", search),
        ];

        payload = {
          dataset_id: datasetId,
          filters: processFiltersArray(combinedFilters),
          update: updatePayload,
          ...(batchGroupId && { batch_group_id: batchGroupId }),
        };
      } else {
        payload = {
          ids: itemIds,
          update: updatePayload,
        };
      }

      const { data } = await api.patch(
        DATASETS_REST_ENDPOINT + "items/batch",
        payload,
      );

      return data;
    },
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "errors", "0"]) ??
        get(error, ["response", "data", "message"]) ??
        error.message;

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
      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetItemBatchUpdateMutation;
