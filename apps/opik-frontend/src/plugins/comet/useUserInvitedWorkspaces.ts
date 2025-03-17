import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { Workspace } from "./types";

const getUserInvitedWorkspaces = async ({ signal }: QueryFunctionContext) => {
  return await api
    .get<Workspace[]>(`/workspaces`, {
      signal,
      params: { withoutExtendedData: true },
    })
    .then(({ data }) => data);
};

// workspaces to which a user has been invited
export default function useUserInvitedWorkspaces(
  options?: QueryConfig<Workspace[]>,
) {
  return useQuery({
    queryKey: ["user-workspaces"],
    queryFn: (context) => getUserInvitedWorkspaces(context),
    ...options,
    enabled: Boolean(options?.enabled),
  });
}
