import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AGENT_INSIGHTS_JOB_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
} from "@/api/api";
import { AgentInsightsJob } from "@/types/signals";
import { useToast } from "@/ui/use-toast";
import { handleMutationError } from "@/api/signals/handleMutationError";

type UseUpdateAgentInsightsGuidanceMutationParams = {
  projectId: string;
  guidance: string;
};

// Saves the project guidance; an empty string clears it. The backend creates the
// job (disabled) when the project has none yet and returns the full job.
const useUpdateAgentInsightsGuidanceMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
      guidance,
    }: UseUpdateAgentInsightsGuidanceMutationParams) => {
      const { data } = await api.put<AgentInsightsJob>(
        `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}/guidance`,
        { guidance },
      );
      return data;
    },
    onSuccess: (job, { projectId }) => {
      // The response is the full job: seed the cache so a quick reopen of the
      // sheet starts from the saved text, not the pre-save one.
      queryClient.setQueryData([AGENT_INSIGHTS_JOB_KEY, { projectId }], job);
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
  });
};

export default useUpdateAgentInsightsGuidanceMutation;
