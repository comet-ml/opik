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
    onSettled: (_data, _error, { promptId }) => {
      // Invalidate the per-prompt and per-prompt-versions caches so anything
      // still loading that prompt (e.g. the Playground) detects the deletion
      // instead of serving stale "exists" data from the cache.
      queryClient.invalidateQueries({ queryKey: ["prompt", { promptId }] });
      queryClient.invalidateQueries({
        predicate: (query) =>
          query.queryKey[0] === "prompt-versions" &&
          typeof query.queryKey[1] === "object" &&
          query.queryKey[1] !== null &&
          "promptId" in query.queryKey[1] &&
          query.queryKey[1].promptId === promptId,
      });
      queryClient.invalidateQueries({ queryKey: ["project-prompts"] });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptDeleteMutation;
