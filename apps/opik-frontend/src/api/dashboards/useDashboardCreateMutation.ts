import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DASHBOARDS_KEY, DASHBOARDS_REST_ENDPOINT } from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseDashboardCreateMutationParams = {
  dashboard: Partial<Dashboard>;
};

type UseDashboardCreateMutationOptions = {
  skipDefaultError?: boolean;
};

const useDashboardCreateMutation = (
  options?: UseDashboardCreateMutationOptions,
) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboard }: UseDashboardCreateMutationParams) => {
      const { headers } = await api.post(DASHBOARDS_REST_ENDPOINT, dashboard);

      const id = extractIdFromLocation(headers?.location);

      return { id };
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
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY],
      });
    },
  });
};

export default useDashboardCreateMutation;
