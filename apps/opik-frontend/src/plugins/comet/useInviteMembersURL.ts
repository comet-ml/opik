import useAppStore from "@/store/AppStore";
import useUser from "@/plugins/comet/useUser";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import { buildUrl } from "@/plugins/comet/utils";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useUserPermissions from "@/plugins/comet/useUserPermissions";

const useInviteMembersURL = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: organizations = [], isLoading } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === workspaceName,
  );
  const organization = organizations?.find((org) => {
    return org.id === workspace?.organizationId;
  });

  const { data: userPermissions = [] } = useUserPermissions(
    {
      userName: user?.userName || "",
      organizationId: workspace?.organizationId || "",
    },
    { enabled: !!user?.loggedIn && !!workspace },
  );

  const isOrganizationAdmin =
    organization?.role === ORGANIZATION_ROLE_TYPE.admin;
  const workspacePermissions = userPermissions?.find(
    (userPermission) => userPermission.workspaceName === workspaceName,
  );
  const invitePermission = workspacePermissions?.permissions.find(
    (permission) => permission.permissionName === "invite_users_to_workspace",
  );
  const canInviteMembers =
    isOrganizationAdmin || invitePermission?.permissionValue === "true";

  if (!canInviteMembers || isLoading) {
    return null;
  }

  return buildUrl(
    "account-settings/workspaces",
    workspaceName,
    `&initialInviteId=${workspace?.workspaceId}`,
  );
};

export default useInviteMembersURL;
