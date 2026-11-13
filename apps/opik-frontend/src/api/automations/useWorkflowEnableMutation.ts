import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
} from "@/api/api";
import { handleMutationError } from "@/api/signals/handleMutationError";
import {
  WorkflowEnabled,
  WorkflowEnableRequest,
} from "@/types/event-automations";
import { useToast } from "@/ui/use-toast";

type UseWorkflowEnableMutationParams = {
  request: WorkflowEnableRequest;
  dryRun?: boolean;
};

const useWorkflowEnableMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      request,
      dryRun = false,
    }: UseWorkflowEnableMutationParams) => {
      const { data } = await api.post<WorkflowEnabled>(
        `${AUTOMATIONS_REST_ENDPOINT}workflows/enable`,
        { created_via: "ui", ...request },
        { params: { dry_run: dryRun } },
      );
      return data;
    },
    onError: (error: AxiosError) => handleMutationError(toast, error),
    onSettled: (_data, _error, { dryRun }) => {
      if (dryRun) return;
      return queryClient.invalidateQueries({
        queryKey: [EVENT_AUTOMATIONS_KEY],
      });
    },
  });
};

export default useWorkflowEnableMutation;
