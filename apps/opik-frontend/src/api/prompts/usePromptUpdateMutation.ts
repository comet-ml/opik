import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { Prompt } from "@/types/prompts";

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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["prompt", { promptId: variables.prompt.id }],
      });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptUpdateMutation;
