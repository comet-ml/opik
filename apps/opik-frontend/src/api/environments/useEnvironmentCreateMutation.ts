import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { ENVIRONMENTS_KEY, ENVIRONMENTS_REST_ENDPOINT } from "@/api/api";
import { Environment } from "@/types/environments";
import { useToast } from "@/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";
import { extractErrorMessage } from "@/lib/errors";

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

      toast({
        title: "Error",
        description: extractErrorMessage(error),
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
