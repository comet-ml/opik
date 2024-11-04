import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { Prompt } from "@/types/prompts";

interface CreatePromptTemplate {
  template: string;
}

type UsePromptCreateMutationParams = {
  prompt: Partial<Prompt> & CreatePromptTemplate;
  workspaceName: string;
};

const usePromptCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      prompt,
      workspaceName,
    }: UsePromptCreateMutationParams) => {
      const { data } = await api.post(PROMPTS_REST_ENDPOINT, {
        ...prompt,
        workspace_name: workspaceName,
      });

      return data;
    },
    onMutate: async (params: UsePromptCreateMutationParams) => {
      return {
        queryKey: ["prompts", { workspaceName: params.workspaceName }],
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
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default usePromptCreateMutation;
