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
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default useRestorePromptVersionMutation;
