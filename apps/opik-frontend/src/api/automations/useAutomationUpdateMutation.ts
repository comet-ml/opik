import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
} from "@/api/api";
import { handleMutationError } from "@/api/signals/handleMutationError";
import { AutomationUpdate } from "@/types/event-automations";
import { useToast } from "@/ui/use-toast";

type UseAutomationUpdateMutationParams = {
  automationId: string;
  update: AutomationUpdate;
};

const useAutomationUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      automationId,
      update,
    }: UseAutomationUpdateMutationParams) => {
      await api.patch(`${AUTOMATIONS_REST_ENDPOINT}${automationId}`, update);
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: [EVENT_AUTOMATIONS_KEY] }),
  });
};

export default useAutomationUpdateMutation;
