import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { FEEDBACK_DEFINITIONS_REST_ENDPOINT } from "@/api/api";
import { CreateFeedbackDefinition } from "@/types/feedback-definitions";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseFeedbackDefinitionCreateMutationParams = {
  feedbackDefinition: CreateFeedbackDefinition;
  workspaceName: string;
};

const useFeedbackDefinitionCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      feedbackDefinition,
      workspaceName,
    }: UseFeedbackDefinitionCreateMutationParams) => {
      const { data } = await api.post(FEEDBACK_DEFINITIONS_REST_ENDPOINT, {
        ...feedbackDefinition,
        workspace_name: workspaceName,
      });
      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      const errorMessage =
        error.response?.status === 409
          ? "A feedback definition with this name already exists. Please choose a different name."
          : message;

      toast({
        title: "Error",
        description: errorMessage,
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      return queryClient.invalidateQueries({
        queryKey: [
          "feedback-definitions",
          { workspaceName: variables.workspaceName },
        ],
      });
    },
  });
};

export default useFeedbackDefinitionCreateMutation;
