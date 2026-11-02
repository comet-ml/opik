import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { JsonUploadFormat } from "@/types/datasets";

type UseDatasetItemsFromJsonMutationParams = {
  datasetId: string;
  jsonFile: File;
  format: JsonUploadFormat;
};

const useDatasetItemsFromJsonMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      datasetId,
      jsonFile,
      format,
    }: UseDatasetItemsFromJsonMutationParams) => {
      const formData = new FormData();
      formData.append("file", jsonFile);
      formData.append("dataset_id", datasetId);
      formData.append("format", format);

      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}items/from-json`,
        formData,
      );
      return data;
    },
    onMutate: async (params: UseDatasetItemsFromJsonMutationParams) => {
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

export default useDatasetItemsFromJsonMutation;
