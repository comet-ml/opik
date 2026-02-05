import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { EnvironmentPointer } from "@/types/blueprints";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

type PromoteToEnvParams = {
  blueprintId: string;
  versionNumber: number;
  env?: string; // defaults to "prod"
};

const promoteToEnv = async ({
  blueprintId,
  versionNumber,
  env = "prod",
}: PromoteToEnvParams): Promise<EnvironmentPointer> => {
  const { data } = await axios.post(
    `${CONFIG_SERVICE_URL}/v1/blueprints/${blueprintId}/promote`,
    { version_number: versionNumber, env },
  );
  return data;
};

export default function usePromoteToProd() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: promoteToEnv,
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["blueprint", "history", variables.blueprintId],
      });
    },
  });
}
