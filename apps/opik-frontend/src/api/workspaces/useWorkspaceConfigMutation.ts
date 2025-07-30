import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  WORKSPACE_CONFIG_REST_ENDPOINT,
  WORKSPACE_CONFIG_KEY,
} from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { WorkspaceConfig } from "@/types/workspaces";

type UseWorkspaceConfigMutationParams = {
  config: WorkspaceConfig;
};

const useWorkspaceConfigMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ config }: UseWorkspaceConfigMutationParams) => {
      const { data } = await api.put(WORKSPACE_CONFIG_REST_ENDPOINT, {
        ...config,
      });

      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "errors", "0"],
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
        queryKey: [WORKSPACE_CONFIG_KEY],
      });
    },
  });
};

export default useWorkspaceConfigMutation;
