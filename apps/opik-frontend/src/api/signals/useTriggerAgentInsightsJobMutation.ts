import { useMutation } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { AGENT_INSIGHTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";

type UseTriggerAgentInsightsJobMutationParams = {
  projectId: string;
};

// "Run diagnostic" triggers an immediate report run (last 24h) for the project.
// The trigger endpoint 404s when no job exists yet, so we lazily create the job
// then retry the trigger. Both calls are fire-and-forget on the backend.
const useTriggerAgentInsightsJobMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
    }: UseTriggerAgentInsightsJobMutationParams) => {
      const triggerUrl = `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}/trigger`;
      try {
        await api.post(triggerUrl);
      } catch (error) {
        if ((error as AxiosError)?.response?.status === 404) {
          await api.post(`${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}`);
          await api.post(triggerUrl);
        } else {
          throw error;
        }
      }
    },
    onSuccess: () => {
      toast({
        title: "Diagnostic started",
        description:
          "We're analysing the last 24h of traces. New issues will appear here once the run completes.",
      });
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
  });
};

export default useTriggerAgentInsightsJobMutation;
