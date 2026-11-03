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
    // PATCH 404s before the project has a job; create it (disabled) and retry,
    // like the trigger mutation does.
    mutationFn: async ({
      projectId,
      status,
    }: UseUpdateAgentInsightsJobMutationParams) => {
      const url = `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}`;
      try {
        const { data } = await api.patch(url, { status });
        return data;
      } catch (error) {
        if ((error as AxiosError)?.response?.status !== 404) throw error;
        await api.post(url);
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
