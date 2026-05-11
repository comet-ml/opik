import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { ENVIRONMENTS_KEY, ENVIRONMENTS_REST_ENDPOINT } from "@/api/api";
import { Environment } from "@/types/environments";
import { useToast } from "@/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type EnvironmentCreatePayload = Pick<Environment, "name"> &
  Partial<Pick<Environment, "description" | "color" | "position">>;

type UseEnvironmentCreateMutationParams = {
  environment: EnvironmentCreatePayload;
};

type UseEnvironmentCreateMutationOptions = {
  showErrorToast?: boolean;
};

const useEnvironmentCreateMutation = ({
  showErrorToast = true,
}: UseEnvironmentCreateMutationOptions = {}) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ environment }: UseEnvironmentCreateMutationParams) => {
      const { headers } = await api.post(ENVIRONMENTS_REST_ENDPOINT, {
        ...environment,
      });

      const id = extractIdFromLocation(headers?.location);

      return { id };
    },
    onError: (error: AxiosError) => {
      if (!showErrorToast) return;

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
        queryKey: [ENVIRONMENTS_KEY],
      });
    },
  });
};

export default useEnvironmentCreateMutation;
