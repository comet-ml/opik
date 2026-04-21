import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { AGENT_CONFIGS_KEY, AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { AgentConfigCreate } from "@/types/agent-configs";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseAgentConfigCreateMutationParams = {
  agentConfig: AgentConfigCreate;
};

const useAgentConfigCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ agentConfig }: UseAgentConfigCreateMutationParams) => {
      const { headers } = await api.patch(
        `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/`,
        agentConfig,
      );

      const id = extractIdFromLocation(headers?.location);

      return { id };
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

export default useAgentConfigCreateMutation;
