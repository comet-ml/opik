import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import axios from "axios";
import { Blueprint } from "@/types/blueprints";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

type UseBlueprintForProjectParams = {
  projectId: string;
  enabled?: boolean;
};

const getBlueprintForProject = async (
  { signal }: QueryFunctionContext,
  projectId: string,
): Promise<Blueprint | null> => {
  try {
    const { data } = await axios.get(`${CONFIG_SERVICE_URL}/v1/blueprints`, {
      signal,
      params: { project_id: projectId },
    });
    return data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      return null;
    }
    throw error;
  }
};

export default function useBlueprintForProject({
  projectId,
  enabled = true,
}: UseBlueprintForProjectParams) {
  return useQuery({
    queryKey: ["blueprint", "project", projectId],
    queryFn: (context) => getBlueprintForProject(context, projectId),
    enabled: enabled && !!projectId,
  });
}
