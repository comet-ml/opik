import useAppStore from "@/store/AppStore";
import useAllWorkspaces from "./useAllWorkspaces";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";

const useCurrentOrganization = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();

  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const currentWorkspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === workspaceName,
  );

  const currentOrganization = organizations?.find((org) => {
    return org.id === currentWorkspace?.organizationId;
  });

  return currentOrganization;
};

export default useCurrentOrganization;
