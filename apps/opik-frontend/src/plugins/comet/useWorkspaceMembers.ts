import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";

interface UseWorkspaceMembersResponse {
  // userName is absent for email-only workspace invitations that have not yet been accepted.
  userName?: string;
  email: string;
  joinedAt?: number;
}

export interface APIWorkspaceMember extends UseWorkspaceMembersResponse {
  isMember: boolean;
}

interface UseAllWorkspaceMembers {
  workspaceId: string;
}

const getWorkspaceMembers = async (
  context: QueryFunctionContext,
  workspaceId: string,
): Promise<APIWorkspaceMember[]> => {
  const response = await api.get<UseWorkspaceMembersResponse[]>(
    `/workspaces/${workspaceId}/getAllMembers`,
    {
      signal: context.signal,
    },
  );

  return response?.data.map((member) => ({
    ...member,
    isMember: true,
  }));
};

const useWorkspaceMembers = (
  { workspaceId }: UseAllWorkspaceMembers,
  options?: QueryConfig<APIWorkspaceMember[]>,
) => {
  return useQuery({
    queryKey: ["workspace-members", { workspaceId }],
    queryFn: (context) => getWorkspaceMembers(context, workspaceId),
    ...options,
  });
};

export default useWorkspaceMembers;
