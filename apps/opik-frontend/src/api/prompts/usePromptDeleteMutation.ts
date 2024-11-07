import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";

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
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptDeleteMutation;
