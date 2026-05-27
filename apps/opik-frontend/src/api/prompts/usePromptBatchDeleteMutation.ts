import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { getApiErrorMessage } from "@/lib/api-error";

type UsePromptBatchDeleteMutationParams = {
  ids: string[];
};

const usePromptBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UsePromptBatchDeleteMutationParams) => {
      const { data } = await api.post(`${PROMPTS_REST_ENDPOINT}delete`, {
        ids: ids,
      });
      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: getApiErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: (_data, _error, { ids }) => {
      // Invalidate the per-prompt and per-prompt-versions caches so anything
      // still loading these prompts (e.g. the Playground) detects the deletion
      // instead of serving stale "exists" data from the cache.
      const deletedIds = new Set(ids);
      queryClient.invalidateQueries({
        predicate: (query) => {
          const [key, params] = query.queryKey;
          if (
            (key === "prompt" || key === "prompt-versions") &&
            typeof params === "object" &&
            params !== null &&
            "promptId" in params
          ) {
            return deletedIds.has(params.promptId as string);
          }
          return false;
        },
      });
      queryClient.invalidateQueries({ queryKey: ["project-prompts"] });
      return queryClient.invalidateQueries({
        queryKey: ["prompts"],
      });
    },
  });
};

export default usePromptBatchDeleteMutation;
