import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, EXPERIMENT_DASHBOARD_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseExperimentDashboardAssociateMutationParams = {
  experimentId: string;
  dashboardId: string;
};

const useExperimentDashboardAssociateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ experimentId, dashboardId }: UseExperimentDashboardAssociateMutationParams) => {
      await api.post(
        `${DASHBOARDS_REST_ENDPOINT}experiments/${experimentId}/associate/${dashboardId}`
      );
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
    onSettled: (_, __, { experimentId }) => {
      queryClient.invalidateQueries({
        queryKey: [EXPERIMENT_DASHBOARD_KEY, { experimentId }],
      });
    },
  });
};

export default useExperimentDashboardAssociateMutation; 
