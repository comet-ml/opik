import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
} from "@/api/api";
import { handleMutationError } from "@/api/signals/handleMutationError";
import { useToast } from "@/ui/use-toast";

type UseWorkflowDisableMutationParams = {
  recipe: string;
  projectId: string;
};

const useWorkflowDisableMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      recipe,
      projectId,
    }: UseWorkflowDisableMutationParams) => {
      const { data } = await api.post<{ disabled: string[] }>(
        `${AUTOMATIONS_REST_ENDPOINT}workflows/disable`,
        { recipe, project_id: projectId },
      );
      return data;
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: [EVENT_AUTOMATIONS_KEY] }),
  });
};

export default useWorkflowDisableMutation;
