import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseDatasetItemsFromCsvMutationParams = {
  datasetId: string;
  csvFile: File;
};

const useDatasetItemsFromCsvMutation = () => {
  const queryClient = useQueryClient();

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
      );
      return data;
    },
    onMutate: async (params: UseDatasetItemsFromCsvMutationParams) => {
      return {
        queryKey: ["dataset-items", { datasetId: params.datasetId }],
      };
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
      queryClient.invalidateQueries({ queryKey: ["project-datasets"] });
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetItemsFromCsvMutation;
