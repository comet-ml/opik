import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

interface Params {
  datasetName: string;
  file: File;
}

const useDatasetCsvUpload = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ datasetName, file }: Params) => {
      const { data } = await api.post<Dataset>(
        `${DATASETS_REST_ENDPOINT}upload-csv?dataset_name=${encodeURIComponent(datasetName)}`,
        file,
        {
          headers: { "Content-Type": "text/csv" },
        },
      );
      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: error.message,
        variant: "destructive",
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["datasets"] });
    },
  });
};

export default useDatasetCsvUpload;
