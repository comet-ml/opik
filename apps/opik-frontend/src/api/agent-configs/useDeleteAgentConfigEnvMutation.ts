import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { AxiosError } from "axios";

import api, { AGENT_CONFIGS_KEY, AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";

type UseDeleteAgentConfigEnvMutationParams = {
  envName: string;
  projectId: string;
};

const useDeleteAgentConfigEnvMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      envName,
      projectId,
    }: UseDeleteAgentConfigEnvMutationParams) => {
      const { data } = await api.delete(
        `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/environments/${encodeURIComponent(
          envName,
        )}/projects/${projectId}`,
      );

      return data;
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
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [AGENT_CONFIGS_KEY],
      });
    },
  });
};

export default useDeleteAgentConfigEnvMutation;
