import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  THREADS_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";

type UseThreadBatchDeleteMutationParams = {
  ids: string[];
  projectId: string;
};

const useThreadBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      projectId,
    }: UseThreadBatchDeleteMutationParams) => {
      const { data } = await api.post(`${TRACES_REST_ENDPOINT}threads/delete`, {
        thread_ids: ids,
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
        queryKey: [
          THREADS_KEY,
          {
            projectId: variables.projectId,
          },
        ],
      });
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

export default useThreadBatchDeleteMutation;
