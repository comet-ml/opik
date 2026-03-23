import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";
import api, {
  AUTOMATIONS_KEY,
  PROJECT_KEY,
  PROJECT_STATISTICS_KEY,
  PROJECTS_KEY,
  PROJECTS_REST_ENDPOINT,
} from "@/api/api";

type UseProjectDeleteMutationParams = {
  projectId: string;
};

const useProjectDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ projectId }: UseProjectDeleteMutationParams) => {
      const { data } = await api.delete(PROJECTS_REST_ENDPOINT + projectId);
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
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [PROJECT_STATISTICS_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [PROJECTS_KEY],
      });
      queryClient.removeQueries({
        queryKey: [PROJECT_KEY],
      });
      queryClient.removeQueries({
        queryKey: [AUTOMATIONS_KEY],
      });
    },
  });
};

export default useProjectDeleteMutation;
