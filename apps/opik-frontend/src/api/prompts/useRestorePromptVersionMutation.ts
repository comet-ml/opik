import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";
import { getApiErrorMessage } from "@/lib/api-error";

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
      toast({
        title: "Error",
        description: getApiErrorMessage(error),
        variant: "destructive",
      });
    },
    onSuccess: async (_, { versionId }) => {
      toast({
        description: `Version ${versionId} has been restored successfully`,
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
      queryClient.invalidateQueries({ queryKey: ["project-prompts"] });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default useRestorePromptVersionMutation;
