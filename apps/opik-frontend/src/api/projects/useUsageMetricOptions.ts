import { useMemo } from "react";
import useTokenUsageNames from "@/api/projects/useTokenUsageNames";
import { resolveProjectSelection } from "@/lib/dashboard/workspaceMetrics";

type UseUsageMetricOptionsParams = {
  runtimeProjectId?: string;
  projectIds: string[];
  allProjects: boolean;
  enabled: boolean;
  selectedUsageMetrics: string[];
};

// Loads span token-usage key names for the widget's project scope (single project vs multi / all projects) and shapes
// them into select options. Already-selected keys are always included so a saved widget's value stays visible even
// when the current scope's data doesn't report it.
const useUsageMetricOptions = ({
  runtimeProjectId,
  projectIds,
  allProjects,
  enabled,
  selectedUsageMetrics,
}: UseUsageMetricOptionsParams) => {
  const selection = resolveProjectSelection({
    runtimeProjectId,
    projectIds,
    allProjects,
  });

  const { data, isPending: isLoadingUsageKeys } = useTokenUsageNames(
    {
      projectId: selection.projectId,
      projectIds: selection.projectIds,
    },
    { enabled },
  );

  const usageKeyOptions = useMemo(() => {
    const names = new Set(data?.names ?? []);
    selectedUsageMetrics.forEach((name) => names.add(name));

    return [...names]
      .sort((a, b) => a.localeCompare(b))
      .map((name) => ({ value: name, label: name }));
  }, [data?.names, selectedUsageMetrics]);

  return { usageKeyOptions, isLoadingUsageKeys };
};

export default useUsageMetricOptions;
