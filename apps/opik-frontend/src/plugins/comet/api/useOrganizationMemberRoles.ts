import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";

export const ORGANIZATION_MEMBER_ROLES_QUERY_KEY = "organization-member-roles";

export type OrganizationMemberRoles = Record<string, ORGANIZATION_ROLE_TYPE>;

// The endpoint refuses longer lists, so they are asked for in chunks rather than truncated: a name
// the map cannot answer for reads as "not an organization admin", which enables controls that must
// stay disabled.
const MAX_USER_NAMES_PER_REQUEST = 500;

interface OrganizationMemberRolesResponse {
  roles: OrganizationMemberRoles;
}

interface UseOrganizationMemberRolesParams {
  organizationId: string;
  userNames: string[];
}

const getOrganizationMemberRoles = async (
  { signal }: QueryFunctionContext,
  organizationId: string,
  userNames: string[],
): Promise<OrganizationMemberRoles> => {
  const roles: OrganizationMemberRoles = {};

  for (
    let from = 0;
    from < userNames.length;
    from += MAX_USER_NAMES_PER_REQUEST
  ) {
    const { data } = await api.post<OrganizationMemberRolesResponse>(
      `/organizations/${organizationId}/members/roles`,
      {
        userNames: userNames.slice(from, from + MAX_USER_NAMES_PER_REQUEST),
      },
      { signal },
    );

    Object.assign(roles, data?.roles);
  }

  return roles;
};

const useOrganizationMemberRoles = (
  { organizationId, userNames }: UseOrganizationMemberRolesParams,
  options?: QueryConfig<OrganizationMemberRoles>,
) => {
  // sorted so that the same set of members in a different order is the same query
  const requestedNames = [...userNames].sort();

  return useQuery({
    queryKey: [
      ORGANIZATION_MEMBER_ROLES_QUERY_KEY,
      { organizationId, userNames: requestedNames.join(",") },
    ],
    queryFn: (context) =>
      getOrganizationMemberRoles(context, organizationId, requestedNames),
    ...options,
  });
};

export default useOrganizationMemberRoles;
