import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAppStore from "@/store/AppStore";

const useWorkspace = (workspaceName?: string) => {
  const activeWorkspaceName = useAppStore((state) => state.activeWorkspaceName);
  const name = workspaceName ?? activeWorkspaceName;

  const { data: allWorkspaces } = useAllWorkspaces();

  const workspace = allWorkspaces?.find((w) => w.workspaceName === name);

  return workspace;
};

export default useWorkspace;
