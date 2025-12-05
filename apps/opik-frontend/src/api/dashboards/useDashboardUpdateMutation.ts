import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  DASHBOARD_KEY,
  DASHBOARDS_KEY,
  DASHBOARDS_REST_ENDPOINT,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { useToast } from "@/components/ui/use-toast";

type UseDashboardUpdateMutationParams = {
  dashboard: Partial<Dashboard>;
};

type UseDashboardUpdateMutationOptions = {
  skipDefaultError?: boolean;
};

const useDashboardUpdateMutation = (
  options?: UseDashboardUpdateMutationOptions,
) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboard }: UseDashboardUpdateMutationParams) => {
      const { data } = await api.patch(
        DASHBOARDS_REST_ENDPOINT + dashboard.id,
        dashboard,
      );
      return data;
    },
    onError: options?.skipDefaultError
      ? undefined
      : (error: AxiosError) => {
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
    onSuccess: (data: Dashboard) => {
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [DASHBOARD_KEY, { dashboardId: data.id }],
      });
    },
  });
};

export default useDashboardUpdateMutation;
