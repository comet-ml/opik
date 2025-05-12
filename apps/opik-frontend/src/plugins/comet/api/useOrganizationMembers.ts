import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
import { OrganizationMember } from "@/plugins/comet/types";

interface UseOrganizationMembers {
  organizationId: string;
}

const getOrganizationMembers = async (
  context: QueryFunctionContext,
  organizationId: string,
) => {
  const response = await api.get<OrganizationMember[]>(
    `/organizations/${organizationId}/members`,
    {
      signal: context.signal,
    },
  );

  return response?.data;
};

const useOrganizationMembers = (
  { organizationId }: UseOrganizationMembers,
  options?: QueryConfig<OrganizationMember[]>,
) => {
  return useQuery({
    queryKey: ["organization-members", { organizationId }],
    queryFn: (context) => getOrganizationMembers(context, organizationId),
    ...options,
  });
};

export default useOrganizationMembers;
