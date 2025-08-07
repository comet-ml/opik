import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";

type UseTraceBatchDeleteMutationParams = {
  ids: string[];
  projectId: string;
};

const useTracesBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      projectId,
    }: UseTraceBatchDeleteMutationParams) => {
      const { data } = await api.post(`${TRACES_REST_ENDPOINT}delete`, {
        ids: ids,
        project_id: projectId,
      });
      return data;
    },
    onError: (error) => {
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
        queryKey: [SPANS_KEY, { projectId: variables.projectId }],
      });
      queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
      queryClient.invalidateQueries({
        queryKey: [
          TRACES_KEY,
          {
            projectId: variables.projectId,
          },
        ],
      });
    },
  });
};

export default useTracesBatchDeleteMutation;
