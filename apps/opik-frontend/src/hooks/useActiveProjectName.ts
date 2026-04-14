import { useActiveProjectId } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import { SNIPPET_PROJECT_NAME } from "@/constants/shared";

const useActiveProjectName = (): string => {
  const activeProjectId = useActiveProjectId();

  const { data } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  return data?.name ?? SNIPPET_PROJECT_NAME;
};

export default useActiveProjectName;
