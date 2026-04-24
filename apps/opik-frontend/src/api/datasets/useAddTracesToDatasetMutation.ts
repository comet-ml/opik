import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";
import { Evaluator } from "@/types/datasets";
import { ExecutionPolicy } from "@/types/test-suites";

export type TraceEnrichmentOptions = {
  include_spans: boolean;
  include_tags: boolean;
  include_feedback_scores: boolean;
  include_comments: boolean;
  include_usage: boolean;
  include_metadata: boolean;
};

type UseAddTracesToDatasetMutationParams = {
  datasetId: string;
  traceIds: string[];
  enrichmentOptions: TraceEnrichmentOptions;
  workspaceName: string;
  evaluators?: Evaluator[];
  executionPolicy?: ExecutionPolicy;
};

const useAddTracesToDatasetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      traceIds,
      enrichmentOptions,
      workspaceName,
      evaluators,
      executionPolicy,
    }: UseAddTracesToDatasetMutationParams) => {
      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}${datasetId}/items/from-traces`,
        {
          trace_ids: traceIds,
          enrichment_options: enrichmentOptions,
          workspace_name: workspaceName,
          ...(evaluators && { evaluators }),
          ...(executionPolicy && { execution_policy: executionPolicy }),
        },
      );
      return data;
    },
    onMutate: async (params: UseAddTracesToDatasetMutationParams) => {
      return {
        queryKey: ["dataset-items", { datasetId: params.datasetId }],
      };
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
    onSettled: (data, error, variables, context) => {
      if (context) {
        queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
      queryClient.invalidateQueries({ queryKey: ["project-datasets"] });
      queryClient.invalidateQueries({ queryKey: ["dataset-versions"] });
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useAddTracesToDatasetMutation;
