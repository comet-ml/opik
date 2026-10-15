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
      const { data } = await api.patch(
        `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}`,
        { status },
      );
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
  });
};

export default useUpdateAgentInsightsJobMutation;
