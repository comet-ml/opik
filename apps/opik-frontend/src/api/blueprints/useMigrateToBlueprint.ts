import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { Blueprint } from "@/types/blueprints";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

type MigrateToBlueprintParams = {
  projectId: string;
  env?: string;
};

type MigrateToBlueprintResponse = Blueprint & {
  version_number: number;
  already_migrated: boolean;
};

const migrateToBlueprint = async ({
  projectId,
  env = "prod",
}: MigrateToBlueprintParams): Promise<MigrateToBlueprintResponse> => {
  const { data } = await axios.post(
    `${CONFIG_SERVICE_URL}/v1/blueprints/migrate`,
    { project_id: projectId, env },
  );
  return data;
};

export default function useMigrateToBlueprint() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: migrateToBlueprint,
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: ["blueprint", "project", data.project_id],
      });
    },
  });
}
