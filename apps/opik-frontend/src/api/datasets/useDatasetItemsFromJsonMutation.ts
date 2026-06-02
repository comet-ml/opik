import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";

export type JsonUploadFormat = "json" | "jsonl";

type UseDatasetItemsFromJsonMutationParams = {
  datasetId: string;
  jsonFile: File;
  format: JsonUploadFormat;
};

const useDatasetItemsFromJsonMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

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
        {
          headers: {
            "Content-Type": "multipart/form-data",
          },
        },
      );
      return data;
    },
    onMutate: async (params: UseDatasetItemsFromJsonMutationParams) => {
      return {
        queryKey: ["dataset-items", { datasetId: params.datasetId }],
      };
    },
    onError: (error: AxiosError) => {
      const serverMessage = get(error, ["response", "data", "message"]);
      const serverErrors = get(error, ["response", "data", "errors"]);
      const joinedErrors = Array.isArray(serverErrors)
        ? serverErrors.join(", ")
        : undefined;
      const message =
        [serverMessage, joinedErrors].filter(Boolean).join(": ") ||
        error.message;

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
      queryClient.invalidateQueries({ queryKey: ["project-datasets"] });
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetItemsFromJsonMutation;
