import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/ui/use-toast";

type UseSetPromptVersionEnvironmentMutationParams = {
  versionId: string;
  promptId: string;
  environment: string | null;
};

const useSetPromptVersionEnvironmentMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      versionId,
      environment,
    }: UseSetPromptVersionEnvironmentMutationParams) => {
      await api.patch(
        `${PROMPTS_REST_ENDPOINT}versions/${versionId}/environments`,
        { environment },
      );
    },
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "message"], error.message) ??
        "An unknown error occurred while updating the prompt environment. Please try again.";

      toast({
        title: "Error",
        description: message,
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
