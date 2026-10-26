import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DEPLOYMENT_VERSIONS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DeploymentVersionHistory } from "@/types/release-notes";

export const DEPLOYMENT_VERSION_HISTORY_QUERY_KEY =
  "deployment-version-history";

const getDeploymentVersionHistory = async ({
  signal,
}: QueryFunctionContext) => {
  const { data } = await api.get<DeploymentVersionHistory>(
    DEPLOYMENT_VERSIONS_REST_ENDPOINT,
    { signal },
  );

  return data;
};

export default function useDeploymentVersionHistory(
  options?: QueryConfig<DeploymentVersionHistory>,
) {
  return useQuery({
    queryKey: [DEPLOYMENT_VERSION_HISTORY_QUERY_KEY, {}],
    queryFn: (context) => getDeploymentVersionHistory(context),
    // The deployed version only changes when the deployment restarts, so refetching on every
    // mount would be pure noise.
    staleTime: Infinity,
    ...options,
  });
}
