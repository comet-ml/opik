import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
} from "@/api/api";
import { handleMutationError } from "@/api/signals/handleMutationError";
import { useToast } from "@/ui/use-toast";

type UseRecommendationDismissMutationParams = {
  recommendationId: string;
  projectId: string;
};

const useRecommendationDismissMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      recommendationId,
      projectId,
    }: UseRecommendationDismissMutationParams) => {
      await api.post(
        `${AUTOMATIONS_REST_ENDPOINT}recommendations/${recommendationId}/dismiss`,
        { project_id: projectId },
      );
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: [EVENT_AUTOMATIONS_KEY] }),
  });
};

export default useRecommendationDismissMutation;
