import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { ORGANIZATION_ROLE_TYPE, Workspace } from "./types";
import useOrganizations from "@/plugins/comet/useOrganizations";

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
  const { data: organizations } = useOrganizations({
    enabled: options?.enabled,
  });

  const organizationIds = organizations
    ?.filter((organization) => {
      return organization.role === ORGANIZATION_ROLE_TYPE.admin;
    })
    .map((organization) => organization.id);

  return useQuery({
    queryKey: ["user-invited-workspaces", { organizationIds }],
    queryFn: (context) => getUserInvitedWorkspaces(context),
    ...options,
    enabled: Boolean(options?.enabled),
  });
}
