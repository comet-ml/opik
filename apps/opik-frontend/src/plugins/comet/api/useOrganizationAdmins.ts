import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
import {
  ORGANIZATION_ROLE_TYPE,
  OrganizationMember,
} from "@/plugins/comet/types";

export const ORGANIZATION_ADMINS_QUERY_KEY = "organization-admins";

interface OrganizationMembersPage {
  data: OrganizationMember[];
  total: number;
}

interface UseOrganizationAdminsParams {
  organizationId: string;
  limit: number;
}

const getOrganizationAdmins = async (
  { signal }: QueryFunctionContext,
  organizationId: string,
  limit: number,
): Promise<OrganizationMember[]> => {
  const { data } = await api.get<OrganizationMembersPage>(
    `/organizations/${organizationId}/members/paged`,
    {
      params: {
        role: ORGANIZATION_ROLE_TYPE.admin,
        pageSize: limit,
      },
      signal,
    },
  );

  return data?.data || [];
};

const useOrganizationAdmins = (
  { organizationId, limit }: UseOrganizationAdminsParams,
  options?: QueryConfig<OrganizationMember[]>,
) => {
  return useQuery({
    queryKey: [ORGANIZATION_ADMINS_QUERY_KEY, { organizationId, limit }],
    queryFn: (context) => getOrganizationAdmins(context, organizationId, limit),
    ...options,
  });
};

export default useOrganizationAdmins;
