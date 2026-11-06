import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import uniq from "lodash/uniq";
import { useToast } from "@/ui/use-toast";
import api, {
  GUARDRAIL_POLICIES_KEY,
  GUARDRAIL_POLICIES_REST_ENDPOINT,
  GUARDRAIL_POLICY_KEY,
} from "@/api/api";

type UseGuardrailPolicyBatchDeleteMutationParams = {
  ids: string[];
};

const useGuardrailPolicyBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    // The API deletes one policy per call. Every call is waited for, so one failure neither hides the others nor
    // leaves a deleted policy in the cache.
    mutationFn: async ({
      ids,
    }: UseGuardrailPolicyBatchDeleteMutationParams) => {
      const results = await Promise.allSettled(
        ids.map((id) => api.delete(`${GUARDRAIL_POLICIES_REST_ENDPOINT}${id}`)),
      );

      // A deleted policy reopened in the same session must not come back from the cache.
      results.forEach((result, index) => {
        if (result.status === "fulfilled") {
          queryClient.removeQueries({
            queryKey: [GUARDRAIL_POLICY_KEY, { policyId: ids[index] }],
          });
        }
      });

      const messages = uniq(
        results.flatMap((result) =>
          result.status === "rejected"
            ? [
                get(
                  result.reason,
                  ["response", "data", "message"],
                  result.reason?.message,
                ),
              ]
            : [],
        ),
      );
      const failed = results.filter(
        (result) => result.status === "rejected",
      ).length;
      if (failed > 0) {
        throw new Error(
          ids.length === 1
            ? messages[0]
            : `${failed} of ${
                ids.length
              } policies could not be deleted: ${messages.join("; ")}`,
        );
      }
    },
    onError: (error) => {
      toast({
        title: "Error",
        description: error.message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [GUARDRAIL_POLICIES_KEY],
      });
    },
  });
};

export default useGuardrailPolicyBatchDeleteMutation;
