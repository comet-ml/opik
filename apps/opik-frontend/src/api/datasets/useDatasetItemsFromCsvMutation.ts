import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetItemsFromCsvMutationParams = {
  datasetId: string;
  csvFile: File;
};

const useDatasetItemsFromCsvMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      csvFile,
    }: UseDatasetItemsFromCsvMutationParams) => {
      const formData = new FormData();
      formData.append("file", csvFile);
      formData.append("dataset_id", datasetId);

      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}items/from-csv`,
        formData,
        {
          headers: {
            "Content-Type": "multipart/form-data",
          },
        },
      );
      return data;
    },
    onMutate: async (params: UseDatasetItemsFromCsvMutationParams) => {
      return {
        queryKey: ["dataset-items", { datasetId: params.datasetId }],
      };
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
    onSettled: (data, error, variables, context) => {
      if (context) {
        queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetItemsFromCsvMutation;
