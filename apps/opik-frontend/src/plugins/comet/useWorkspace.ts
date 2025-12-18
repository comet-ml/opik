import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAppStore from "@/store/AppStore";

const useWorkspace = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: allWorkspaces } = useAllWorkspaces();

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === workspaceName,
  );

  return workspace;
};

export default useWorkspace;
