import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { Workspace } from "./types";

const getUserInvitedWorkspaces = async ({ signal }: QueryFunctionContext) => {
  const response = await api.get<Workspace[]>(`/workspaces`, {
    signal,
    params: { withoutExtendedData: true },
  });

  return response.data;
};

// workspaces to which a user has been invited
export default function useUserInvitedWorkspaces(
  options?: QueryConfig<Workspace[]>,
) {
  return useQuery({
    // enabled: true is needed just to overcome the eslint requirement to have parameters
    queryKey: ["user-invited-workspaces", { enabled: true }],
    queryFn: (context) => getUserInvitedWorkspaces(context),
    ...options,
    enabled: Boolean(options?.enabled),
  });
}
