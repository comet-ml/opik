import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";
import { Prompt } from "@/types/prompts";
import { getApiErrorMessage } from "@/lib/api-error";

type UsePromptUpdateMutationParams = {
  prompt: Partial<Prompt>;
};

const usePromptUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ prompt }: UsePromptUpdateMutationParams) => {
      const { id: promptId, ...restPrompt } = prompt;

      const { data } = await api.put(
        `${PROMPTS_REST_ENDPOINT}${promptId}`,
        restPrompt,
      );

      return data;
    },

    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: getApiErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["prompt", { promptId: variables.prompt.id }],
      });
      queryClient.invalidateQueries({ queryKey: ["project-prompts"] });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptUpdateMutation;
