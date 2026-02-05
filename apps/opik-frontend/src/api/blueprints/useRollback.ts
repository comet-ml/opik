import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { EnvironmentPointer } from "@/types/blueprints";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

type RollbackParams = {
  blueprintId: string;
  versionNumber: number;
};

const rollback = async ({
  blueprintId,
  versionNumber,
}: RollbackParams): Promise<EnvironmentPointer> => {
  const { data } = await axios.post(
    `${CONFIG_SERVICE_URL}/v1/blueprints/${blueprintId}/rollback`,
    { version_number: versionNumber },
  );
  return data;
};

export default function useRollback() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: rollback,
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["blueprint", "history", variables.blueprintId],
      });
    },
  });
}
