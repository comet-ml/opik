import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, {
  ENVIRONMENT_KEY,
  ENVIRONMENTS_KEY,
  ENVIRONMENTS_REST_ENDPOINT,
} from "@/api/api";
import { Environment } from "@/types/environments";
import { useToast } from "@/ui/use-toast";
import { extractErrorMessage } from "@/lib/errors";

type EnvironmentUpdatePayload = Partial<
  Pick<Environment, "name" | "description" | "color" | "position">
>;

type UseEnvironmentUpdateMutationParams = {
  environmentId: string;
  environment: EnvironmentUpdatePayload;
};

type UseEnvironmentUpdateMutationOptions = {
  showErrorToast?: boolean;
};

const useEnvironmentUpdateMutation = ({
  showErrorToast = true,
}: UseEnvironmentUpdateMutationOptions = {}) => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      environmentId,
      environment,
    }: UseEnvironmentUpdateMutationParams) => {
      const { data } = await api.patch(
        ENVIRONMENTS_REST_ENDPOINT + environmentId,
        environment,
      );
      return data;
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
      queryClient.invalidateQueries({
        queryKey: [ENVIRONMENT_KEY],
      });
    },
  });
};

export default useEnvironmentUpdateMutation;
