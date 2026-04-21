import { useCallback, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import sortBy from "lodash/sortBy";
import toLower from "lodash/toLower";

import { useToast } from "@/ui/use-toast";
import { calculateWorkspaceName } from "@/lib/utils";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useUser from "@/plugins/comet/useUser";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useAppStore from "@/store/AppStore";
import {
  Workspace,
  Organization,
  ORGANIZATION_ROLE_TYPE,
} from "@/plugins/comet/types";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { buildUrl } from "@/plugins/comet/utils";

const useWorkspaceSelectorData = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [isOrgSubmenuOpen, setIsOrgSubmenuOpen] = useState(false);

  const { data: user } = useUser();
  const { data: userInvitedWorkspaces, isLoading } = useUserInvitedWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const currentOrganization = useCurrentOrganization();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleOpenChange = useCallback(
    (open: boolean) => {
      if (!open && isOrgSubmenuOpen) {
        setIsOrgSubmenuOpen(false);
        return;
      }

      setIsDropdownOpen(open);
      if (!open) {
        setSearch("");
      }
    },
    [isOrgSubmenuOpen],
  );

  const handleChangeWorkspace = useCallback(
    (newWorkspace: Workspace) => {
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: newWorkspace.workspaceName },
      });
    },
    [navigate],
  );

  const handleChangeOrganization = useCallback(
    (newOrganization: Organization) => {
      const newOrganizationWorkspaces =
        userInvitedWorkspaces?.filter(
          (workspace) => workspace.organizationId === newOrganization.id,
        ) || [];

      if (newOrganizationWorkspaces.length === 0) {
        toast({
          description: `You are not part of any workspaces in ${newOrganization.name}, please ask to be invited to one`,
          variant: "destructive",
        });
        return;
      }

      const newWorkspace =
        newOrganizationWorkspaces.find((workspace) => workspace.default) ||
        newOrganizationWorkspaces[0];

      if (newWorkspace) {
        navigate({
          to: "/$workspaceName",
          params: { workspaceName: newWorkspace.workspaceName },
        });
      }
    },
    [navigate, userInvitedWorkspaces, toast],
  );

  const handleOrgSettingsClick = useCallback(() => {
    if (currentOrganization && workspaceName) {
      window.location.href = buildUrl(
        `organizations/${currentOrganization.id}`,
        workspaceName,
      );
    }
  }, [currentOrganization, workspaceName]);

  const memberWorkspaces = useMemo(() => {
    if (!userInvitedWorkspaces || !currentOrganization) return [];
    return userInvitedWorkspaces.filter(
      (workspace) =>
        workspace.organizationId === currentOrganization.id &&
        workspace.workspaceName !== DEFAULT_WORKSPACE_NAME,
    );
  }, [userInvitedWorkspaces, currentOrganization]);

  const filteredWorkspaces = useMemo(() => {
    if (!search) return memberWorkspaces;

    const searchLower = toLower(search);
    return memberWorkspaces.filter((workspace) => {
      const displayName = calculateWorkspaceName(workspace.workspaceName);
      return toLower(displayName).includes(searchLower);
    });
  }, [memberWorkspaces, search]);

  const sortedWorkspaces = useMemo(
    () => sortBy(filteredWorkspaces, "workspaceName"),
    [filteredWorkspaces],
  );

  const sortedOrganizations = useMemo(() => {
    if (!organizations) return [];
    return sortBy(organizations, "name");
  }, [organizations]);

  const hasMultipleOrganizations = organizations && organizations.length > 1;
  const hasMemberWorkspaces = memberWorkspaces.length > 0;
  const shouldShowDropdown = hasMemberWorkspaces || hasMultipleOrganizations;
  const isOrgAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  return {
    user,
    workspaceName,
    currentOrganization,
    isLoading,
    organizations,

    search,
    setSearch,
    isDropdownOpen,
    setIsDropdownOpen,
    isOrgSubmenuOpen,
    setIsOrgSubmenuOpen,

    handleOpenChange,
    handleChangeWorkspace,
    handleChangeOrganization,
    handleOrgSettingsClick,

    sortedWorkspaces,
    sortedOrganizations,

    shouldShowDropdown,
    hasMemberWorkspaces,
    hasMultipleOrganizations,
    isOrgAdmin,
  };
};

export default useWorkspaceSelectorData;
