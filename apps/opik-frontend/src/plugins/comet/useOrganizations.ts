import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { Organization } from "./types";

const getOrganizations = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<Organization[]>("/organizations", {
    params: { withoutExtendedData: true },
    signal,
  });

  return data;
};

export default function useOrganizations(
  options?: QueryConfig<Organization[]>,
) {
  return useQuery({
    queryKey: ["organizations", {}],
    queryFn: (context) => getOrganizations(context),
    ...options,
  });
}
