import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { getApiErrorMessage } from "@/lib/api-error";

type UsePromptDeleteMutationParams = {
  promptId: string;
};

const usePromptDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ promptId }: UsePromptDeleteMutationParams) => {
      const { data } = await api.delete(PROMPTS_REST_ENDPOINT + promptId);
      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: getApiErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["project-prompts"] });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptDeleteMutation;
