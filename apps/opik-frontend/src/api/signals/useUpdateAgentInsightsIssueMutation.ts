import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  AGENT_INSIGHTS_ISSUE_KEY,
  AGENT_INSIGHTS_ISSUES_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
} from "@/api/api";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";
import { useToast } from "@/ui/use-toast";

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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUES_KEY] });
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUE_KEY] });
    },
  });
};

export default useUpdateAgentInsightsIssueMutation;
