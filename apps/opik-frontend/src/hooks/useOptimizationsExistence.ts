import useOptimizationsList from "@/api/optimizations/useOptimizationsList";

type UseOptimizationsExistenceParams = {
  workspaceName: string;
  projectId?: string;
};

// Unfiltered size=1 probe so the empty state is decided independently of the
// filtered/paginated list (filters/search can't trigger the onboarding screen).
export const useOptimizationsExistence = ({
  workspaceName,
  projectId,
}: UseOptimizationsExistenceParams) => {
  const { data, isPending } = useOptimizationsList({
    workspaceName,
    projectId,
    page: 1,
    size: 1,
  });

  // Empty only on a successful zero-count probe; while pending/errored `data`
  // is undefined, so callers fall through to the table instead of hiding runs.
  return { isEmpty: data?.total === 0, isPending };
};
