import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { ORGANIZATION_ROLE_TYPE, Workspace } from "./types";
import useOrganizations from "./useOrganizations";

const getAllUserWorkspaces = async (
  { signal }: QueryFunctionContext,
  { organizationIds }: { organizationIds?: string[] } = {},
) => {
  const workspacesPromises = organizationIds?.map((organizationId) => {
    return api
      .get<Workspace[]>(`/workspaces`, {
        signal,
        params: { organizationId, withoutExtendedData: true },
      })
      .then(({ data }) => data);
  });

  return (await Promise.all(workspacesPromises || [])).flat();
};

// the workspaces of all organizations where a user is admin
// we can't take all organizations because it throws 403 error
export default function useAdminOrganizationWorkspaces(
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
    queryKey: ["user-admin-organization-workspaces", { organizationIds }],
    queryFn: (context) =>
      getAllUserWorkspaces(context, {
        organizationIds,
      }),
    ...options,
    enabled: options?.enabled && !!organizations,
  });
}
