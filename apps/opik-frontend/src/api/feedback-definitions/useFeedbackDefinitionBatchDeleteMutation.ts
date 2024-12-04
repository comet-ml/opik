import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { FEEDBACK_DEFINITIONS_REST_ENDPOINT } from "@/api/api";

type UseFeedbackDefinitionBatchDeleteMutationParams = {
  ids: string[];
};

const useFeedbackDefinitionBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
    }: UseFeedbackDefinitionBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${FEEDBACK_DEFINITIONS_REST_ENDPOINT}delete`,
        {
          ids: ids,
        },
      );
      return data;
    },
    onError: (error) => {
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: ["feedback-definitions"],
      });
    },
  });
};

export default useFeedbackDefinitionBatchDeleteMutation;
