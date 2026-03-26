import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, {
  INSIGHTS_VIEW_KEY,
  INSIGHTS_VIEWS_KEY,
  INSIGHTS_VIEWS_REST_ENDPOINT,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { useToast } from "@/ui/use-toast";

type UseInsightsViewUpdateMutationParams = {
  dashboard: Partial<Dashboard>;
};

type UseInsightsViewUpdateMutationOptions = {
  skipDefaultError?: boolean;
  retry?: number;
  retryDelay?: number;
};

const useInsightsViewUpdateMutation = (
  options?: UseInsightsViewUpdateMutationOptions,
) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    retry: options?.retry ?? 0,
    retryDelay: options?.retryDelay,
    mutationFn: async ({ dashboard }: UseInsightsViewUpdateMutationParams) => {
      const { data } = await api.patch(
        INSIGHTS_VIEWS_REST_ENDPOINT + dashboard.id,
        dashboard,
      );
      return data;
    },
    onMutate: async ({ dashboard }: UseInsightsViewUpdateMutationParams) => {
      const queryKey = [INSIGHTS_VIEW_KEY, { dashboardId: dashboard.id }];

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
              [
                INSIGHTS_VIEW_KEY,
                { dashboardId: context.previousDashboard.id },
              ],
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
        queryKey: [INSIGHTS_VIEWS_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [INSIGHTS_VIEW_KEY, { dashboardId: data.id }],
      });
    },
  });
};

export default useInsightsViewUpdateMutation;
