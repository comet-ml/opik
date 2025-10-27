import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { AUTOMATIONS_KEY } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

const MANUAL_EVALUATION_REST_ENDPOINT = "/v1/private/manual-evaluation/";

type ManualEvaluationEntityType = "trace" | "thread";

type ManualEvaluationRequest = {
  project_id: string;
  entity_ids: string[];
  rule_ids: string[];
  entity_type: ManualEvaluationEntityType;
};

type ManualEvaluationResponse = {
  entities_queued: number;
  rules_applied: number;
};

type UseManualEvaluationMutationParams = {
  projectId: string;
  entityIds: string[];
  ruleIds: string[];
  entityType: ManualEvaluationEntityType;
};

const useManualEvaluationMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
      entityIds,
      ruleIds,
      entityType,
    }: UseManualEvaluationMutationParams) => {
      const endpoint =
        entityType === "trace"
          ? `${MANUAL_EVALUATION_REST_ENDPOINT}traces`
          : `${MANUAL_EVALUATION_REST_ENDPOINT}threads`;

      const requestBody: ManualEvaluationRequest = {
        project_id: projectId,
        entity_ids: entityIds,
        rule_ids: ruleIds,
        entity_type: entityType,
      };

      const { data } = await api.post<ManualEvaluationResponse>(
        endpoint,
        requestBody,
      );
      return data;
    },
    onSuccess: (data, variables) => {
      const entityLabel = variables.entityType === "trace" ? "traces" : "threads";
      
      toast({
        title: "Evaluation queued",
        description: `${data.entities_queued} ${entityLabel} sent for evaluation with ${data.rules_applied} ${data.rules_applied === 1 ? "rule" : "rules"}`,
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

export default useManualEvaluationMutation;

