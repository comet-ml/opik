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
    onMutate: async ({ dashboard }: UseDashboardUpdateMutationParams) => {
      const queryKey = [DASHBOARD_KEY, { dashboardId: dashboard.id }];

      await queryClient.cancelQueries({ queryKey });

      const previousDashboard = queryClient.getQueryData<Dashboard>(queryKey);

      queryClient.setQueryData<Dashboard>(queryKey, (previous) =>
        previous ? { ...previous, ...dashboard } : (dashboard as Dashboard),
      );

      return { previousDashboard };
    },
    onError: options?.skipDefaultError
      ? undefined
      : (error: AxiosError, _variables, context) => {
          if (context?.previousDashboard) {
            queryClient.setQueryData(
              [DASHBOARD_KEY, { dashboardId: context.previousDashboard.id }],
              context.previousDashboard,
            );
          }

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
