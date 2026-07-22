import useProjectTokenUsageNames, {
  TokenUsageNamesResponse,
} from "@/api/projects/useProjectTokenUsageNames";
import useWorkspaceTokenUsageNames from "@/api/projects/useWorkspaceTokenUsageNames";
import { QueryConfig } from "@/api/api";

type UseTokenUsageNamesParams = {
  // Exactly one of projectId / projectIds identifies the scope, mirroring useMetricData.
  projectId?: string;
  projectIds?: string[];
};

// Picks the right token-usage-names source: a single project uses the per-project endpoint; a project set (or "all
// projects", signalled by a projectIds array) uses the workspace endpoint that enumerates keys across projects. Both
// return the same TokenUsageNamesResponse shape, so callers consume one result regardless of scope.
const useTokenUsageNames = (
  params: UseTokenUsageNamesParams,
  config?: QueryConfig<TokenUsageNamesResponse>,
) => {
  const isWorkspace = Array.isArray(params.projectIds);

  const projectQuery = useProjectTokenUsageNames(
    {
      projectId: params.projectId ?? "",
    },
    {
      ...config,
      enabled: !isWorkspace && !!params.projectId && (config?.enabled ?? true),
    },
  );

  const workspaceQuery = useWorkspaceTokenUsageNames(
    {
      projectIds: params.projectIds ?? [],
    },
    {
      ...config,
      enabled: isWorkspace && (config?.enabled ?? true),
    },
  );

  return isWorkspace ? workspaceQuery : projectQuery;
};

export default useTokenUsageNames;
