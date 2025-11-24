import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt-version"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
    },
    onError: (error) => {
      toast({
        variant: "destructive",
        description: error.message || "Failed to update prompt versions",
      });
    },
  });
}
