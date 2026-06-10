import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";
import { getApiErrorMessage } from "@/lib/api-error";

type UseSetPromptVersionEnvironmentMutationParams = {
  versionId: string;
  promptId: string;
  environments: string[];
};

const useSetPromptVersionEnvironmentMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      versionId,
      environments,
    }: UseSetPromptVersionEnvironmentMutationParams) => {
      await api.patch(
        `${PROMPTS_REST_ENDPOINT}versions/${versionId}/environments`,
        { environments },
      );
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: getApiErrorMessage(
          error,
          "An unknown error occurred while updating the prompt environment. Please try again.",
        ),
        variant: "destructive",
      });
    },
    onSuccess: (_data, { promptId }) => {
      queryClient.invalidateQueries({
        queryKey: ["prompt", { promptId }],
      });
      queryClient.invalidateQueries({
        predicate: (query) =>
          query.queryKey[0] === "prompt-versions" &&
          typeof query.queryKey[1] === "object" &&
          query.queryKey[1] !== null &&
          "promptId" in query.queryKey[1] &&
          query.queryKey[1].promptId === promptId,
      });
      queryClient.invalidateQueries({ queryKey: ["prompt-version"] });
    },
  });
};

export default useSetPromptVersionEnvironmentMutation;
