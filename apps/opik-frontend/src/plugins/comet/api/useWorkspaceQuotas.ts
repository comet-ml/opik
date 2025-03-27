import { useQuery } from "@tanstack/react-query";
import api from "../api";
import { QUOTA_TYPE } from "@/plugins/comet/types/quotas";

interface WorkspaceQuota {
  limit: number;
  type: QUOTA_TYPE;
  used: number;
}

interface WorkspaceQuotasResponse {
  quotas: WorkspaceQuota[];
}

interface UseWorkspaceQuotas {
  workspaceName: string;
}

const getWorkspaceQuotas = async (context, workspaceName: string) => {
  const response = await api.get<WorkspaceQuotasResponse>(
    "/organizations/quotas",
    {
      params: { workspaceName },
    },
  );

  return response?.data?.quotas;
};

const useWorkspaceQuotas = ({ workspaceName }: UseWorkspaceQuotas, options) => {
  return useQuery({
    queryKey: ["workspace-quotas", { workspaceName }],
    queryFn: (context) => getWorkspaceQuotas(context, workspaceName),
    ...options,
  });
};

export default useWorkspaceQuotas;
