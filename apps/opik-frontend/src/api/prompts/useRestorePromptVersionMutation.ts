import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseRestorePromptVersionMutationParams = {
  promptId: string;
  versionId: string;
};

const useRestorePromptVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      promptId,
      versionId,
    }: UseRestorePromptVersionMutationParams) => {
      const { data } = await api.post(
        `${PROMPTS_REST_ENDPOINT}${promptId}/versions/${versionId}/restore`,
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
    onSuccess: async (_, { versionId }) => {
      toast({
        description: `Version ${versionId} has been restored successfully`,
      });
    },
    onSettled: (_, __, { promptId }) => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompts"] });
      // Invalidate the specific prompt to update latest_version
      return queryClient.invalidateQueries({ queryKey: ["prompt", { promptId }] });
    },
  });
};

export default useRestorePromptVersionMutation;
