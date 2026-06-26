import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AGENT_INSIGHTS_ISSUE_KEY,
  AGENT_INSIGHTS_ISSUES_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
} from "@/api/api";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";
import { useToast } from "@/ui/use-toast";
import { handleMutationError } from "@/api/signals/handleMutationError";

type UseUpdateAgentInsightsIssueMutationParams = {
  issueId: string;
  projectId: string;
  status: AGENT_INSIGHTS_ISSUE_STATUS;
};

const useUpdateAgentInsightsIssueMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      issueId,
      projectId,
      status,
    }: UseUpdateAgentInsightsIssueMutationParams) => {
      const { data } = await api.patch(
        `${AGENT_INSIGHTS_REST_ENDPOINT}issues/${issueId}`,
        { project_id: projectId, status },
      );
      return data;
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUES_KEY] });
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUE_KEY] });
    },
  });
};

export default useUpdateAgentInsightsIssueMutation;
