import useUser from "@/plugins/comet/useUser";
import useWorkspaceByName from "@/plugins/comet/useWorkspaceByName";
import useAppStore from "@/store/AppStore";

// Resolves the workspace with one point read rather than downloading every workspace of the
// organization and picking a row here. Returns undefined while in flight and when the workspace is
// not visible to the caller, which is what the previous .find() over the list returned.
const useWorkspace = (workspaceName?: string) => {
  const activeWorkspaceName = useAppStore((state) => state.activeWorkspaceName);
  const name = workspaceName ?? activeWorkspaceName;

  const { data: user } = useUser();
  const { data: workspace } = useWorkspaceByName(
    { workspaceName: name },
    { enabled: !!user?.loggedIn },
  );

  return workspace ?? undefined;
};

export default useWorkspace;
