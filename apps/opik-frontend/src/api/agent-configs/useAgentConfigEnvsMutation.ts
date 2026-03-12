import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { AxiosError } from "axios";

import api, { AGENT_CONFIGS_KEY, AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { AgentConfigEnvsRequest } from "@/types/agent-configs";
import { useToast } from "@/components/ui/use-toast";

type UseAgentConfigEnvsMutationParams = {
  envsRequest: AgentConfigEnvsRequest;
};

const useAgentConfigEnvsMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ envsRequest }: UseAgentConfigEnvsMutationParams) => {
      const { data } = await api.post(
        `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/environments`,
        envsRequest,
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

export default useAgentConfigEnvsMutation;
