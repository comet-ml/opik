import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";

export interface UseWorkspaceMembersResponse {
  userName: string;
  email: string;
  joinedAt?: string;
}

interface UseAllWorkspaceMembers {
  workspaceId: string;
}

const getWorkspaceMembers = async (
  context: QueryFunctionContext,
  workspaceId: string,
) => {
  const response = await api.get<UseWorkspaceMembersResponse[]>(
    `/workspaces/${workspaceId}/getAllMembers`,
    {
      signal: context.signal,
    },
  );

  return response?.data;
};

const useWorkspaceMembers = (
  { workspaceId }: UseAllWorkspaceMembers,
  options?: QueryConfig<UseWorkspaceMembersResponse[]>,
) => {
  return useQuery({
    queryKey: ["workspace-members", { workspaceId }],
    queryFn: (context) => getWorkspaceMembers(context, workspaceId),
    ...options,
  });
};

export default useWorkspaceMembers;
