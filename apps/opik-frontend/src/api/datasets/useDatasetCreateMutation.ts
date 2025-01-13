import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseDatasetCreateMutationParams = {
  dataset: Partial<Dataset>;
  workspaceName: string;
};

const useDatasetCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      dataset,
      workspaceName,
    }: UseDatasetCreateMutationParams) => {
      const { data, headers } = await api.post(DATASETS_REST_ENDPOINT, {
        ...dataset,
        workspace_name: workspaceName,
      });

      // TODO workaround to return just created resource while implementation on BE is not done
      return data
        ? data
        : {
            ...dataset,
            id: extractIdFromLocation(headers?.location),
          };
    },
    onMutate: async (params: UseDatasetCreateMutationParams) => {
      return {
        queryKey: ["datasets", { workspaceName: params.workspaceName }],
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
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useDatasetCreateMutation;
