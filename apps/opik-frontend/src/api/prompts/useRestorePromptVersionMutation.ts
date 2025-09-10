import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";

type UseRestorePromptVersionMutationParams = {
  promptId: string;
  versionId: string;
  onSuccess: (promptVersion: PromptVersion) => void;
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
    onSuccess: async (data: PromptVersion, { onSuccess }) => {
      toast({
        title: "Version restored",
        description: "The prompt version has been successfully restored.",
      });

      onSuccess(data);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
    },
  });
};

export default useRestorePromptVersionMutation;
