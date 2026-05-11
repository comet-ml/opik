import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, {
  ENVIRONMENT_KEY,
  ENVIRONMENTS_KEY,
  ENVIRONMENTS_REST_ENDPOINT,
} from "@/api/api";
import { Environment } from "@/types/environments";
import { useToast } from "@/ui/use-toast";

type EnvironmentUpdatePayload = Partial<
  Pick<Environment, "name" | "description" | "color" | "position">
>;

type UseEnvironmentUpdateMutationParams = {
  environmentId: string;
  environment: EnvironmentUpdatePayload;
};

const useEnvironmentUpdateMutation = () => {
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
      queryClient.invalidateQueries({
        queryKey: [ENVIRONMENT_KEY],
      });
    },
  });
};

export default useEnvironmentUpdateMutation;
