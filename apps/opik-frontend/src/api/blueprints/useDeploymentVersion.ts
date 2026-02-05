import { useQuery } from "@tanstack/react-query";
import axios from "axios";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

export type DeploymentVersionSnapshot = {
  prompts: Record<string, unknown>;
  config?: Record<string, unknown>;
};

export type DeploymentVersionDetail = {
  id: string;
  blueprint_id: string;
  version_number: number;
  snapshot: DeploymentVersionSnapshot;
  change_summary: string | null;
  change_type: string;
  source_experiment_id: string | null;
  created_at: string;
  created_by: string | null;
};

type UseDeploymentVersionParams = {
  blueprintId: string;
  versionNumber: number;
  enabled?: boolean;
};

const getDeploymentVersion = async (
  blueprintId: string,
  versionNumber: number,
): Promise<DeploymentVersionDetail> => {
  const { data } = await axios.get(
    `${CONFIG_SERVICE_URL}/v1/blueprints/${blueprintId}/versions/by-number/${versionNumber}`,
  );
  return data;
};

export default function useDeploymentVersion({
  blueprintId,
  versionNumber,
  enabled = true,
}: UseDeploymentVersionParams) {
  return useQuery({
    queryKey: ["deployment-version", blueprintId, versionNumber],
    queryFn: () => getDeploymentVersion(blueprintId, versionNumber),
    enabled: enabled && !!blueprintId && versionNumber > 0,
  });
}
