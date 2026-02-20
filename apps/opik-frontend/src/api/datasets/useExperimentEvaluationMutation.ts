import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import api, { AUTOMATIONS_KEY } from "@/api/api";
import get from "lodash/get";

const EXPERIMENT_EVALUATION_REST_ENDPOINT =
  "/v1/private/manual-evaluation/experiments";

type ExperimentEvaluationRequest = {
  project_id: string;
  entity_ids: string[];
  rule_ids: string[];
  entity_type: "trace";
};

type ExperimentEvaluationResponse = {
  entities_queued: number;
  rules_applied: number;
};

type UseExperimentEvaluationMutationParams = {
  projectId: string;
  experimentIds: string[];
  ruleIds: string[];
};

const useExperimentEvaluationMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
      experimentIds,
      ruleIds,
    }: UseExperimentEvaluationMutationParams) => {
      const requestBody: ExperimentEvaluationRequest = {
        project_id: projectId,
        entity_ids: experimentIds,
        rule_ids: ruleIds,
        entity_type: "trace",
      };

      const { data } = await api.post<ExperimentEvaluationResponse>(
        EXPERIMENT_EVALUATION_REST_ENDPOINT,
        requestBody,
      );
      return data;
    },
    onSuccess: () => {
      toast({
        title: "Evaluation queued",
        description: `The experiment traces have been queued for scoring. Processing time may vary based on the number of traces. You can view the results in the Experiments table and in the experiment details view.`,
      });
    },
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "message"]) ??
        "An unknown error occurred while queueing experiment evaluation; please try again later.";

      toast({
        title: "Error",
        description: `Failed to trigger evaluation: ${message}`,
        variant: "destructive",
      });
    },
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [AUTOMATIONS_KEY],
      });
    },
  });
};

export default useExperimentEvaluationMutation;
