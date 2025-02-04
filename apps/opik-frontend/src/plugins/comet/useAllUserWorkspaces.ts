import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import uniqBy from "lodash/uniqBy";
import api, { QueryConfig } from "./api";
import { ORGANIZATION_ROLE_TYPE, Workspace } from "./types";
import useOrganizations from "./useOrganizations";

const getAllUserWorkspaces = async (
  { signal }: QueryFunctionContext,
  { organizationIds }: { organizationIds?: string[] } = {},
) => {
  const allWorkspacesPromise = api
    .get<Workspace[]>(`/workspaces`, {
      signal,
      params: { withoutExtendedData: true },
    })
    .then(({ data }) => data);

  const workspacesPromises = organizationIds?.map((organizationId) => {
    return api
      .get<Workspace[]>(`/workspaces`, {
        signal,
        params: { organizationId, withoutExtendedData: true },
      })
      .then(({ data }) => data);
  });

  const workspaces = await Promise.all([
    allWorkspacesPromise,
    ...(workspacesPromises || []),
  ]);

  return uniqBy(workspaces.flat(), "workspaceId");
};

export default function useAllUserWorkspaces(
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
    queryKey: ["workspaces", { organizationIds }],
    queryFn: (context) => getAllUserWorkspaces(context, { organizationIds }),
    ...options,
    enabled: options?.enabled && !!organizations,
  });
}
