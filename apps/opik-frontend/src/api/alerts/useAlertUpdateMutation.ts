import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { ALERTS_KEY, ALERTS_REST_ENDPOINT } from "@/api/api";
import { Alert } from "@/types/alerts";
import { useToast } from "@/components/ui/use-toast";

type UseAlertUpdateMutationParams = {
  alert: Partial<Alert>;
  alertId: string;
};

const useAlertUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ alert, alertId }: UseAlertUpdateMutationParams) => {
      const { data } = await api.put(
        `${ALERTS_REST_ENDPOINT}${alertId}`,
        alert,
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [ALERTS_KEY],
      });
    },
  });
};

export default useAlertUpdateMutation;
