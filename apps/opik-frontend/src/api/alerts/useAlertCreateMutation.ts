import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { ALERTS_KEY, ALERTS_REST_ENDPOINT } from "@/api/api";
import { Alert } from "@/types/alerts";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseAlertCreateMutationParams = {
  alert: Partial<Alert>;
};

const useAlertCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ alert }: UseAlertCreateMutationParams) => {
      const { data } = await api.post(ALERTS_REST_ENDPOINT, {
        ...alert,
      });
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

export default useAlertCreateMutation;
