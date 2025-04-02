import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
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

const getWorkspaceQuotas = async (
  context: QueryFunctionContext,
  workspaceName: string,
) => {
  const response = await api.get<WorkspaceQuotasResponse>(
    "/organizations/quotas",
    {
      params: { workspaceName },
      signal: context.signal,
    },
  );

  return response?.data?.quotas;
};

const useWorkspaceQuotas = (
  { workspaceName }: UseWorkspaceQuotas,
  options: QueryConfig<WorkspaceQuota[]>,
) => {
  return useQuery({
    queryKey: ["workspace-quotas", { workspaceName }],
    queryFn: (context) => getWorkspaceQuotas(context, workspaceName),
    ...options,
  });
};

export default useWorkspaceQuotas;
