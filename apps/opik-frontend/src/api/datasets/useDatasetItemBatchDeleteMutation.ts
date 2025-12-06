import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Filters } from "@/types/filters";
import {
  generateSearchByFieldFilters,
  processFiltersArray,
} from "@/lib/filters";

type UseDatasetItemBatchDeleteMutationParams = {
  ids: string[];
  isAllItemsSelected?: boolean;
  filters?: Filters;
  search?: string;
};

const useDatasetItemBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      isAllItemsSelected,
      filters = [],
      search,
    }: UseDatasetItemBatchDeleteMutationParams) => {
      let payload;

      if (isAllItemsSelected) {
        const combinedFilters = [
          ...filters,
          ...generateSearchByFieldFilters("data", search),
        ];

        payload = {
          filters: processFiltersArray(combinedFilters),
        };
      } else {
        payload = { item_ids: ids };
      }

      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}items/delete`,
        payload,
      );
      return data;
    },
    onSuccess: (_, { ids, isAllItemsSelected }) => {
      const isSingle = !isAllItemsSelected && ids.length === 1;
      toast({
        title: isSingle ? "Dataset item removed" : "Dataset items removed",
        description: isSingle
          ? "The dataset item has been removed. Don't forget to save your changes to create a new version."
          : "The dataset items have been removed. Don't forget to save your changes to create a new version.",
      });
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
        queryKey: ["dataset-items"],
      });
    },
  });
};

export default useDatasetItemBatchDeleteMutation;
