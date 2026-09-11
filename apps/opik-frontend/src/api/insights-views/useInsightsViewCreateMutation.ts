import { AxiosError } from "axios";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";
import api, {
  INSIGHTS_VIEWS_KEY,
  INSIGHTS_VIEWS_REST_ENDPOINT,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { extractIdFromLocation } from "@/lib/utils";

type UseInsightsViewCreateMutationParams = {
  dashboard: Partial<Dashboard>;
};

type UseInsightsViewCreateMutationOptions = {
  skipDefaultError?: boolean;
};

const useInsightsViewCreateMutation = (
  options?: UseInsightsViewCreateMutationOptions,
) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboard }: UseInsightsViewCreateMutationParams) => {
      const { headers } = await api.post(
        INSIGHTS_VIEWS_REST_ENDPOINT,
        dashboard,
      );

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
        queryKey: [INSIGHTS_VIEWS_KEY],
      });
    },
  });
};

export default useInsightsViewCreateMutation;
