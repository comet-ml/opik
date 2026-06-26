import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AGENT_INSIGHTS_JOB_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
} from "@/api/api";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import { useToast } from "@/ui/use-toast";
import { handleMutationError } from "@/api/signals/handleMutationError";

type UseUpdateAgentInsightsJobMutationParams = {
  projectId: string;
  status: AGENT_INSIGHTS_JOB_STATUS;
};

const useUpdateAgentInsightsJobMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
      status,
    }: UseUpdateAgentInsightsJobMutationParams) => {
      const url = `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}`;
      try {
        const { data } = await api.patch(url, { status });
        return data;
      } catch (error) {
        // No job row yet (404) → create it (backend creates it enabled), then
        // apply the requested status if it isn't already enabled.
        if ((error as AxiosError)?.response?.status !== 404) throw error;
        await api.post(url);
        if (status === AGENT_INSIGHTS_JOB_STATUS.enabled) return undefined;
        const { data } = await api.patch(url, { status });
        return data;
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
  });
};

export default useUpdateAgentInsightsJobMutation;
