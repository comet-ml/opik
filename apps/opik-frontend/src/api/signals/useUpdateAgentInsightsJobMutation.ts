import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  AGENT_INSIGHTS_JOB_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
} from "@/api/api";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import { useToast } from "@/ui/use-toast";

type UseUpdateAgentInsightsJobMutationParams = {
  projectId: string;
  status: AGENT_INSIGHTS_JOB_STATUS;
};

// PATCH the per-project job status (enable/disable the daily diagnostic).
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
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );
      toast({ title: "Error", description: message, variant: "destructive" });
    },
  });
};

export default useUpdateAgentInsightsJobMutation;
