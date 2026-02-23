import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

// NOTE: Prompt versions still use the legacy tags + mergeTags approach.
// Reason: The backend SQL query for prompt versions to handle bulk tag removals across
// multiple items would get quite complex. The simpler replace/merge approach is
// sufficient for current prompt version tag management use cases.
type UsePromptVersionsUpdateMutationParams = {
  versionIds: string[];
  tags: string[];
  mergeTags: boolean;
};

const updatePromptVersions = async ({
  versionIds,
  tags,
  mergeTags,
}: UsePromptVersionsUpdateMutationParams) => {
  await api.patch(`${PROMPTS_REST_ENDPOINT}versions`, {
    ids: versionIds,
    update: {
      tags,
    },
    merge_tags: mergeTags,
  });
};

export default function usePromptVersionsUpdateMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: updatePromptVersions,
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
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt-version"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
    },
  });
}
